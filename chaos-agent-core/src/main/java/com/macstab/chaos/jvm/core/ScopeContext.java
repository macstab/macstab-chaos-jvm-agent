package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSession;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-local session-ID stack that propagates chaos session context across method calls and
 * thread boundaries.
 *
 * <h2>Stack semantics</h2>
 *
 * <p>Sessions can be nested: each call to {@link #bind(String)} pushes a session ID onto the
 * per-thread {@link java.util.Deque} stack and returns a {@link AutoCloseable} binding whose {@code
 * close()} pops it. {@link #currentSessionId()} peeks at the top of the stack.
 *
 * <h2>Thread-boundary propagation</h2>
 *
 * <p>To carry the session ID across a thread hand-off, use {@link #wrap(String, Runnable)} or
 * {@link #wrap(String, Callable)} to snapshot the current session ID and push it onto the worker
 * thread's stack for the duration of the task. The ID is automatically popped when the task
 * completes, even on exception.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Each thread owns its own per-thread {@link java.util.Deque}. To let cross-thread close
 * correctly target the opener's deque (e.g. Spring {@code @PreDestroy} on a servlet thread closing
 * a session opened on an app-startup thread), {@link #bind(String)} and {@link #bindRoot(String)}
 * <em>capture the opener's deque reference at bind time</em> and the returned {@link
 * ChaosSession.ScopeBinding}'s close closes over that reference instead of re-resolving {@code
 * sessionStack.get()} on the closing thread.
 *
 * <p>Because the deque may therefore be mutated concurrently — hot-path push/peek/pop on the owning
 * thread while a foreign thread runs {@code close()} — the backing collection is {@link
 * ConcurrentLinkedDeque}, which is wait-free and safe under concurrent access. {@link
 * ConcurrentLinkedDeque#peek()} stays O(1) on the hot path ({@link #currentSessionId()}).
 */
final class ScopeContext {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  private final ThreadLocal<Deque<String>> sessionStack =
      ThreadLocal.withInitial(ConcurrentLinkedDeque::new);

  /**
   * Returns the session ID at the top of the current thread's stack, or {@code null} if no session
   * is bound on this thread.
   *
   * @return the innermost active session ID, or {@code null}
   */
  String currentSessionId() {
    return sessionStack.get().peek();
  }

  /**
   * Pushes {@code sessionId} onto the current thread's session stack and returns a binding that
   * pops it on {@link AutoCloseable#close()}.
   *
   * <p>Intended for use in a try-with-resources block:
   *
   * <pre>{@code
   * try (var binding = scopeContext.bind(sessionId)) {
   *     // current thread sees sessionId as currentSessionId()
   * }
   * }</pre>
   *
   * <p>The {@link ThreadLocal} entry is intentionally retained even when the stack empties — once
   * captured at bind time, the deque reference is closed over by the binding's close lambda so
   * cross-thread close targets the opener's stack. Clearing the {@link ThreadLocal} on the owner
   * thread does not clear the captured reference, and tearing the {@link ThreadLocal} down from a
   * foreign thread would target the foreign thread's mapping instead. The empty-deque retention
   * cost per thread is a reference to an empty {@link ConcurrentLinkedDeque}; negligible.
   *
   * @param sessionId the session ID to push; must not be {@code null}
   * @return an {@link AutoCloseable} that pops the session ID when closed
   */
  ChaosSession.ScopeBinding bind(final String sessionId) {
    // Capture the opener's deque reference at bind time. Without this the close lambda would
    // resolve sessionStack.get() on whatever thread happens to run close(), which can be a
    // completely foreign thread (framework teardown path, executor hand-off) — and silently pop
    // an unrelated session off *that* thread's stack. Closing over the deque pins the
    // pop to the right stack even when close() runs cross-thread; ConcurrentLinkedDeque keeps
    // the concurrent push/pop/peek safe.
    final Deque<String> stack = sessionStack.get();
    stack.push(sessionId);
    return () -> {
      // Verify the top matches what this binding pushed. A mismatch means either a
      // double-close or a close() invoked in the wrong order (outer binding closed while an
      // inner binding is still live). Popping anyway would silently corrupt the stack for every
      // subsequent request served by this pooled thread — a subtle, hard-to-diagnose leak of
      // scope between unrelated requests. Fail loud at the call site instead.
      // removeFirstOccurrence atomically finds and removes the entry in a single pass,
      // closing the TOCTOU window between a separate peek() and pop() where a concurrent
      // push() from another thread (cross-thread close is allowed by the class contract)
      // could insert a new head between the peek and pop, causing pop() to remove the wrong
      // entry and leave this binding's sessionId permanently stranded on the stack.
      if (!stack.removeFirstOccurrence(sessionId)) {
        throw new IllegalStateException(
            "ScopeBinding close() could not find sessionId='"
                + sessionId
                + "' on the stack (double-close or wrong-order close?)");
      }
    };
  }

  /**
   * Root-session variant of {@link #bind(String)}: pushes {@code sessionId} onto the current
   * thread's session stack and returns a binding whose {@code close()} removes the tail-most
   * occurrence of {@code sessionId} rather than requiring strict LIFO order.
   *
   * <p>This exists because sibling sessions opened on the same thread are peers rather than nested
   * scopes — a test that opens sessions S0, S1, S2 on one thread should be free to close them in
   * any order (S0-first is a natural "insertion order" close, not a bug). Applying LIFO to root
   * bindings would force callers to reverse-order-close sibling sessions, which neither the JUnit
   * extension lifecycle nor typical test code does.
   *
   * <p>Nested bindings created via {@link #bind(String)} still enforce strict LIFO — they guard the
   * thread-pool-leak bug that motivated HIGH-59. Since root session IDs are unique UUIDs, a nested
   * {@code bind()} that happens to reuse this session's ID (for instance, an explicit {@code
   * session.bind()} re-entry on the same thread) sits at the stack head while the root sits at the
   * tail; {@code removeLastOccurrence} unambiguously targets the root entry and leaves the nested
   * entry — and its LIFO guarantee — untouched.
   *
   * @param sessionId the session ID to push; must not be {@code null}
   * @return an {@link AutoCloseable} that removes this session's root entry when closed
   */
  ChaosSession.ScopeBinding bindRoot(final String sessionId) {
    // Capture the opener's deque reference at bind time so cross-thread close (Spring
    // @PreDestroy on a servlet thread, JUnit afterAll on a different worker, etc.) targets the
    // deque the session was actually pushed onto — not whatever random stack happens to live on
    // the closing thread. ConcurrentLinkedDeque makes this safe under concurrent access from
    // the owner thread's hot path (push/peek) and the closer's removeLastOccurrence.
    final Deque<String> stack = sessionStack.get();
    stack.push(sessionId);
    return () -> {
      if (stack.isEmpty()) {
        throw new IllegalStateException(
            "session root binding close() called but the stack is empty (double-close?)");
      }
      // removeLastOccurrence targets the tail-most entry, which for a root binding is the
      // original push (sibling roots pushed later are closer to the head; a nested bind with
      // the same id would also be closer to the head). Removing the tail preserves LIFO
      // ordering of every entry above it on the stack.
      if (!stack.removeLastOccurrence(sessionId)) {
        throw new IllegalStateException(
            "session root binding close() could not find sessionId='"
                + sessionId
                + "' on the stack");
      }
    };
  }

  /**
   * Returns a {@link Runnable} that, when executed on any thread, pushes {@code sessionId} onto
   * that thread's session stack, runs {@code runnable}, then pops the ID — even if {@code runnable}
   * throws.
   *
   * <p>Use this to propagate the current chaos session across an executor hand-off:
   *
   * <pre>{@code
   * executor.submit(scopeContext.wrap(currentId, task));
   * }</pre>
   *
   * @param sessionId the session ID to propagate; must not be {@code null}
   * @param runnable the task to execute within the session scope
   * @return a wrapped {@link Runnable}
   */
  Runnable wrap(final String sessionId, final Runnable runnable) {
    return new ScopedRunnable(this, sessionId, runnable);
  }

  /**
   * Returns a {@link Callable} that, when executed on any thread, pushes {@code sessionId} onto
   * that thread's session stack, calls {@code callable}, pops the ID, and returns the result — even
   * if {@code callable} throws.
   *
   * @param <T> the return type of the callable
   * @param sessionId the session ID to propagate; must not be {@code null}
   * @param callable the task to execute within the session scope
   * @return a wrapped {@link Callable}
   */
  <T> Callable<T> wrap(final String sessionId, final Callable<T> callable) {
    return new ScopedCallable<>(this, sessionId, callable);
  }

  private record ScopedRunnable(ScopeContext scopeContext, String sessionId, Runnable delegate)
      implements Runnable {
    @Override
    public void run() {
      final ChaosSession.ScopeBinding binding = scopeContext.bind(sessionId);
      try {
        delegate.run();
      } finally {
        try {
          binding.close();
        } catch (final RuntimeException scopeCloseFailure) {
          // Do not let scope cleanup failure propagate as the task's primary exception.
          // A stale or double-closed binding (e.g. from a leaked inner binding on the same
          // thread) must not mask the actual result of the task or corrupt the executor.
          LOGGER.log(
              Level.FINE,
              "chaos scope binding close failed; session stack may be inconsistent",
              scopeCloseFailure);
        }
      }
    }
  }

  private record ScopedCallable<T>(
      ScopeContext scopeContext, String sessionId, Callable<T> delegate) implements Callable<T> {
    @Override
    public T call() throws Exception {
      final ChaosSession.ScopeBinding binding = scopeContext.bind(sessionId);
      try {
        return delegate.call();
      } finally {
        try {
          binding.close();
        } catch (final RuntimeException scopeCloseFailure) {
          LOGGER.log(
              Level.FINE,
              "chaos scope binding close failed; session stack may be inconsistent",
              scopeCloseFailure);
        }
      }
    }
  }
}
