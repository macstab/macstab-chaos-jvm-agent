package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Probe main for {@link AgentOwnWorkIntegrationTest}: each mode activates a scenario that selects
 * what the agent itself does while it manages another scenario (its log lines, its event hand-off,
 * its stressor teardown), lets the agent do that work, and prints the selecting scenario's counters
 * as {@code key=value} lines. The agent is attached by {@link ChaosPlatform#installLocally()}.
 */
public final class AgentOwnWorkProbeMain {

  private AgentOwnWorkProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "log-lines" -> logLines();
      case "async-listener" -> asyncListener();
      case "debug-dump" -> debugDump();
      case "direct-buffer-close" -> directBufferClose();
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  /**
   * A 2 s delay on the first file write, then a scenario activated and stopped: the agent's STARTED
   * and STOPPED lines go through JUL's console handler to {@code System.err}.
   */
  private static void logLines() {
    // The application's JUL, initialised first, so the agent's lines are written at once.
    Logger.getLogger("probe").info("probe start");
    final ChaosControlPlane plane = ChaosPlatform.installLocally();
    // Woven before the timed region: the first scenario of its hook group weaves it.
    plane.activate(gcSuppress("warm")).close();
    final ChaosActivationHandle writes =
        plane.activate(
            ChaosScenario.builder("file-writes")
                .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)))
                .effect(ChaosEffect.delay(WRITE_DELAY))
                .activationPolicy(ActivationPolicy.count(1))
                .build());
    final long started = System.nanoTime();
    plane.activate(gcSuppress("other")).close();
    final long elapsed = (System.nanoTime() - started) / 1_000_000L;
    final long matched = writes.matchedCount();
    // Closed before printing: the probe's own output is a file write too.
    writes.close();
    System.out.println("elapsedMillis=" + elapsed);
    System.out.println("matched=" + matched);
  }

  /**
   * The delay of a file write of a scenario: a call it applied to takes at least this long, far
   * above what the agent's own work takes (the tests' bound is half of it).
   */
  private static final Duration WRITE_DELAY = Duration.ofSeconds(2);

  /**
   * A reject of every queue offer and an asynchronous listener, then a scenario activated and
   * stopped: the agent hands each of its events to the listener's queue.
   */
  private static void asyncListener() throws InterruptedException {
    final ChaosControlPlane plane = ChaosPlatform.installLocally();
    final List<String> received = new CopyOnWriteArrayList<>();
    plane.addEventListener(
        ChaosEventListener.async(
            event -> {
              if ("other".equals(event.scenarioId())) {
                received.add(event.type().name());
              }
            }));
    final ChaosActivationHandle offers =
        plane.activate(
            ChaosScenario.builder("offers")
                .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)))
                .effect(ChaosEffect.reject("full"))
                .activationPolicy(ActivationPolicy.always())
                .build());
    plane.activate(gcSuppress("other")).close();
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (received.size() < 3 && System.nanoTime() - deadline < 0) {
      Thread.sleep(10L);
    }
    System.out.println("received=" + String.join(",", received));
    System.out.println(
        "dropped=" + plane.diagnostics().snapshot().runtimeDetails().get("droppedEvents"));
    System.out.println("matched=" + offers.matchedCount());
    offers.close();
  }

  /** A delay on the first file write, then a plan that prints its debug dump on start. */
  private static void debugDump() {
    final ChaosControlPlane plane = ChaosPlatform.installLocally();
    plane.activate(gcSuppress("warm")).close();
    final ChaosActivationHandle writes =
        plane.activate(
            ChaosScenario.builder("file-writes")
                .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)))
                .effect(ChaosEffect.delay(WRITE_DELAY))
                .activationPolicy(ActivationPolicy.count(1))
                .build());
    final long started = System.nanoTime();
    plane
        .activate(
            new ChaosPlan(
                new ChaosPlan.Metadata("dump", ""),
                new ChaosPlan.Observability(true, false, true),
                List.of(gcSuppress("dumped"))))
        .close();
    final long elapsed = (System.nanoTime() - started) / 1_000_000L;
    final long matched = writes.matchedCount();
    // Closed before printing: the probe's own output is a file write too.
    writes.close();
    System.out.println("elapsedMillis=" + elapsed);
    System.out.println("matched=" + matched);
  }

  /**
   * 64 MiB of direct buffers and a reject of every reflective call, then the buffers' scenario
   * stopped: the stressor frees its buffers through their cleaners, reflectively.
   */
  private static void directBufferClose() {
    final ChaosControlPlane plane = ChaosPlatform.installLocally();
    final BufferPoolMXBean direct =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(pool -> "direct".equals(pool.getName()))
            .findFirst()
            .orElseThrow();
    final ChaosActivationHandle buffers =
        plane.activate(
            ChaosScenario.builder("buffers")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                .effect(ChaosEffect.directBufferPressure(64L << 20, 1 << 20))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final ChaosActivationHandle reflection =
        plane.activate(
            ChaosScenario.builder("reflection")
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)))
                .effect(ChaosEffect.reject("no reflection"))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final long during = direct.getMemoryUsed();
    buffers.close();
    final long after = direct.getMemoryUsed();
    System.out.println("freedMiB=" + (during - after) / (1024 * 1024));
    System.out.println("matched=" + reflection.matchedCount());
    reflection.close();
  }

  private static ChaosScenario gcSuppress(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
        .effect(ChaosEffect.suppress())
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
