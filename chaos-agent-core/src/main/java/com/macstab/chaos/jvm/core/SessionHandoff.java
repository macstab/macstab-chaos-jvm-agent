package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSession;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/**
 * Carries a session from the thread that hands work off to the thread that runs it, for the
 * hand-offs that are not executor submissions: {@code ForkJoinPool} submissions and forks (which
 * covers {@code CompletableFuture.*Async} and parallel streams), {@code Thread.start} and virtual
 * threads.
 *
 * <p>At the hand-off the current session is recorded against the carrier (the task or the thread)
 * by the carrier's identity ({@link IdentityWeakKey}), held weakly, so a carrier that never runs
 * leaves nothing behind and two equal carriers keep their own sessions. When the carrier runs, the
 * session is bound for the duration of the run and released afterwards.
 *
 * <p>The tables are concurrent maps and this class takes no lock of its own: when no hand-off is
 * pending or running, the very frequent run-side calls return after an {@code isEmpty()} check, and
 * otherwise allocate one short-lived lookup key. The keys of collected carriers are removed when
 * the next capture comes, or when a run finds nothing recorded.
 */
final class SessionHandoff {
  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");

  private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
  private final ConcurrentHashMap<IdentityWeakKey, String> captured = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<IdentityWeakKey, ChaosSession.ScopeBinding> active =
      new ConcurrentHashMap<>();

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
    expungeCollected();
    captured.put(new IdentityWeakKey(carrier, collected), sessionId);
  }

  /**
   * Binds the session recorded for {@code carrier}, if any, on the current thread.
   *
   * @param carrier the task or thread starting to run
   * @param scopeContext the session stacks
   */
  void enter(final Object carrier, final ScopeContext scopeContext) {
    if (carrier == null || captured.isEmpty()) {
      return;
    }
    // A thread's session must be bound on that thread. Early JDK 21 builds enter
    // VirtualThread.run(Runnable) on the carrier and mount inside it; the capture then waits for
    // the hook after mount(), which runs on the virtual thread.
    if (carrier instanceof Thread thread && thread != Thread.currentThread()) {
      return;
    }
    final String sessionId = captured.remove(new IdentityWeakKey(carrier));
    if (sessionId == null) {
      // Captures whose carrier never ran (a Thread subclass overriding run(), a cancelled task)
      // stay until their carrier is collected: drop those, so the isEmpty() fast path returns.
      expungeCollected();
      return;
    }
    active.put(new IdentityWeakKey(carrier), scopeContext.bind(sessionId));
  }

  /**
   * Releases the session bound by {@link #enter} for {@code carrier}.
   *
   * @param carrier the task or thread that finished running
   */
  void exit(final Object carrier) {
    if (carrier == null || active.isEmpty()) {
      return;
    }
    final ChaosSession.ScopeBinding binding = active.remove(new IdentityWeakKey(carrier));
    if (binding == null) {
      return;
    }
    try {
      binding.close();
    } catch (final RuntimeException closeFailure) {
      LOGGER.log(Level.FINE, closeFailure, () -> "chaos session hand-off release failed");
    }
  }

  /** Removes the captures whose carrier was collected before it ran. */
  private void expungeCollected() {
    for (Reference<?> key = collected.poll(); key != null; key = collected.poll()) {
      captured.remove(key);
    }
  }
}
