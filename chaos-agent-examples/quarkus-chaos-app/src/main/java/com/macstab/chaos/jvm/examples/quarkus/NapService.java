package com.macstab.chaos.jvm.examples.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;

/** An application bean whose work is a short sleep: the call a test's chaos makes slow. */
@ApplicationScoped
public class NapService {

  /** Creates the bean; called by CDI. */
  public NapService() {}

  /**
   * Sleeps one millisecond.
   *
   * @return how long the sleep took, in milliseconds
   * @throws InterruptedException if the thread is interrupted while it sleeps
   */
  public long nap() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
