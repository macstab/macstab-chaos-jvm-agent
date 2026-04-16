package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosSession;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

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
 * <p>The {@link ThreadLocal} itself is thread-safe by definition — each thread has its own {@link
 * java.util.Deque} instance. No cross-thread synchronization is required.
 */
final class ScopeContext {
  private final ThreadLocal<Deque<String>> sessionStack = ThreadLocal.withInitial(ArrayDeque::new);

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
   * <p>If the stack is empty after the pop, the {@link ThreadLocal} entry is removed to avoid
   * memory leaks in thread-pool scenarios.
   *
   * @param sessionId the session ID to push; must not be {@code null}
   * @return an {@link AutoCloseable} that pops the session ID when closed
   */
  ChaosSession.ScopeBinding bind(final String sessionId) {
    sessionStack.get().push(sessionId);
    return () -> {
      final Deque<String> stack = sessionStack.get();
      if (!stack.isEmpty()) {
        stack.pop();
      }
      if (stack.isEmpty()) {
        sessionStack.remove();
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
      try (ChaosSession.ScopeBinding ignored = scopeContext.bind(sessionId)) {
        delegate.run();
      }
    }
  }

  private record ScopedCallable<T>(
      ScopeContext scopeContext, String sessionId, Callable<T> delegate) implements Callable<T> {
    @Override
    public T call() throws Exception {
      try (ChaosSession.ScopeBinding ignored = scopeContext.bind(sessionId)) {
        return delegate.call();
      }
    }
  }
}
