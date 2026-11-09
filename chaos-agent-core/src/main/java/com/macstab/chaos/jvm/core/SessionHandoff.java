package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSession;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/**
 * Carries a session from the thread that hands work off to the thread that runs it, for the
 * hand-offs that are not executor submissions: {@code ForkJoinPool} submissions and forks (which
 * covers {@code CompletableFuture.*Async} and parallel streams), {@code Thread.start} and virtual
 * threads.
 *
 * <p>At the hand-off the current session is recorded against the carrier (the task or the thread)
 * in a weak table, so a carrier that never runs leaves nothing behind. When the carrier runs, the
 * session is bound for the duration of the run and released afterwards. Thread and fork-join task
 * identity is object identity (neither overrides {@code equals}).
 *
 * <p>Both tables are touched only while a session hand-off is pending or active: the counters let
 * the very frequent run-side calls return without locking when no session is in play.
 */
final class SessionHandoff {
  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  private final Map<Object, String> captured = Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<Object, ChaosSession.ScopeBinding> active =
      Collections.synchronizedMap(new IdentityHashMap<>());
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicInteger running = new AtomicInteger();

  /**
   * Records {@code sessionId} for {@code carrier}; nothing happens without a session.
   *
   * @param carrier the task or thread the work is handed to
   * @param sessionId the session of the handing-off thread, or {@code null}
   */
  void capture(final @Nullable Object carrier, final @Nullable String sessionId) {
    if (carrier == null || sessionId == null) {
      return;
    }
    // The counter changes only together with the table, under the table's own lock: a resync in
    // enter() cannot then reset it between this put and its increment.
    synchronized (captured) {
      if (captured.put(carrier, sessionId) == null) {
        pending.incrementAndGet();
      }
    }
  }

  /**
   * Binds the session recorded for {@code carrier}, if any, on the current thread.
   *
   * @param carrier the task or thread starting to run
   * @param scopeContext the session stacks
   */
  void enter(final Object carrier, final ScopeContext scopeContext) {
    if (carrier == null || pending.get() == 0) {
      return;
    }
    // A thread's session must be bound on that thread. Early JDK 21 builds enter
    // VirtualThread.run(Runnable) on the carrier and mount inside it; the capture then waits for
    // the hook after mount(), which runs on the virtual thread.
    if (carrier instanceof Thread thread && thread != Thread.currentThread()) {
      return;
    }
    final String sessionId;
    synchronized (captured) {
      sessionId = captured.remove(carrier);
      if (sessionId == null) {
        // Captures whose carrier never ran (a Thread subclass overriding run(), a cancelled task)
        // are dropped by the weak map without decrementing; resync, so the fast path returns.
        if (captured.isEmpty()) {
          pending.set(0);
        }
        return;
      }
      pending.decrementAndGet();
    }
    active.put(carrier, scopeContext.bind(sessionId));
    running.incrementAndGet();
  }

  /**
   * Releases the session bound by {@link #enter} for {@code carrier}.
   *
   * @param carrier the task or thread that finished running
   */
  void exit(final Object carrier) {
    if (carrier == null || running.get() == 0) {
      return;
    }
    final ChaosSession.ScopeBinding binding = active.remove(carrier);
    if (binding == null) {
      return;
    }
    running.decrementAndGet();
    try {
      binding.close();
    } catch (final RuntimeException closeFailure) {
      LOGGER.log(Level.FINE, closeFailure, () -> "chaos session hand-off release failed");
    }
  }
}
