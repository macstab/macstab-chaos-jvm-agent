package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

final class ThreadAdvice {
  private ThreadAdvice() {}

  /** Marks {@code ThreadPoolExecutor.addWorker}: threads started inside it are pool workers. */
  static final class PoolWorkerScopeAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_POOL_WORKER);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_POOL_WORKER);
    }
  }

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

  /**
   * {@code Thread.run()} and {@code VirtualThread.run(Runnable)}: a started thread runs in the
   * session of the thread that started it, bound for the whole run. A {@code Thread} subclass that
   * overrides {@code run()} without calling {@code super.run()} does not get the session.
   */
  static final class RunSessionAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This final Thread thread) {
      BootstrapDispatcher.enterSession(thread);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.This final Thread thread) {
      BootstrapDispatcher.exitSession(thread);
    }
  }

  /**
   * {@code VirtualThread.mount()} exit: on JDK builds that mount inside {@code run(Runnable)}
   * (21.0.0 to 21.0.2), the virtual thread becomes current only here, so this is where its session
   * is bound. Later mounts find nothing to bind.
   */
  static final class MountSessionAdvice {
    @Advice.OnMethodExit
    static void exit(@Advice.This final Thread thread) {
      BootstrapDispatcher.enterSession(thread);
    }
  }
}
