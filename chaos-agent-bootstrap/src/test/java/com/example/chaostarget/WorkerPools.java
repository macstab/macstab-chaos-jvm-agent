package com.example.chaostarget;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Thread pools of an application that override the JDK's worker template method. */
public final class WorkerPools {

  private WorkerPools() {}

  /** A pool whose {@code beforeExecute} does not call {@code super}, as Spring's executors do. */
  public static final class Overriding extends ThreadPoolExecutor {
    public Overriding() {
      super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    protected void beforeExecute(final Thread worker, final Runnable task) {
      Thread.onSpinWait();
    }
  }

  /** A pool whose {@code beforeExecute} calls {@code super}: still one worker run per task. */
  public static final class Delegating extends ThreadPoolExecutor {
    public Delegating() {
      super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    protected void beforeExecute(final Thread worker, final Runnable task) {
      super.beforeExecute(worker, task);
    }
  }
}
