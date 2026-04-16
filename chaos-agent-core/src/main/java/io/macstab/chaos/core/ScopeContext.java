package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosSession;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

final class ScopeContext {
  private final ThreadLocal<Deque<String>> sessionStack = ThreadLocal.withInitial(ArrayDeque::new);

  String currentSessionId() {
    return sessionStack.get().peek();
  }

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

  Runnable wrap(final String sessionId, final Runnable runnable) {
    return new ScopedRunnable(this, sessionId, runnable);
  }

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
