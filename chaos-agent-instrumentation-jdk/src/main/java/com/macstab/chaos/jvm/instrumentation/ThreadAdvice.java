package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ThreadAdvice {
  private ThreadAdvice() {}

  /**
   * Advice for {@code Thread.start()}, {@code Thread.start(ThreadContainer)} and {@code
   * VirtualThread.start(ThreadContainer)}. Thread-per-task executors ({@code
   * Executors.newVirtualThreadPerTaskExecutor()}) and structured concurrency start threads through
   * {@code start(ThreadContainer)}, bypassing {@code start()}; {@code VirtualThread.start()} itself
   * delegates to {@code start(ThreadContainer)}. The {@code SCOPE_THREAD_START} call-depth scope
   * evaluates one start once.
   */
  static final class StartAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Thread thread) throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_THREAD_START)) {
        try {
          BootstrapDispatcher.beforeThreadStart(thread);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_START);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_THREAD_START);
    }
  }
}
