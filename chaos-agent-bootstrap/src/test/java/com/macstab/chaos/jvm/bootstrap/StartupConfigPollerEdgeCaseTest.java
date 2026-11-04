package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the poller handles plans and runtimes that fail, change detection at the edges of the file
 * stamp, and a close that races a reload.
 */
@DisplayName("StartupConfigPoller edge cases")
class StartupConfigPollerEdgeCaseTest {

  @TempDir Path tempDir;

  private ChaosRuntime runtime;
  private ChaosActivationHandle handle;
  private Path config;
  private StartupConfigPoller poller;

  @BeforeEach
  void setUp() throws Exception {
    runtime = mock(ChaosRuntime.class);
    handle = mock(ChaosActivationHandle.class);
    config = tempDir.resolve("plan.json");
    write(planJson("a"));
  }

  @AfterEach
  void tearDown() {
    if (poller != null) {
      poller.close();
    }
  }

  private StartupConfigPoller poller(final String interval) {
    poller =
        StartupConfigPoller.createIfEnabled(
                "configWatchInterval=" + interval, Map.of(), config, runtime)
            .orElseThrow();
    return poller;
  }

  private static String planJson(final String id) {
    return """
        {"metadata":{"name":"p"},"scenarios":[{"id":"%s","scope":"JVM",
         "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
         "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """
        .formatted(id);
  }

  /** Writes the plan with a modification time well in the past, so no read is provisional. */
  private void write(final String json) throws Exception {
    Files.writeString(config, json);
    stamp(60_000);
  }

  private void stamp(final long millisAgo) throws Exception {
    Files.setLastModifiedTime(config, FileTime.fromMillis(System.currentTimeMillis() - millisAgo));
  }

  private static String stderrOf(final ThrowingRunnable action) throws Exception {
    final PrintStream original = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      action.run();
    } finally {
      System.setErr(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static ChaosScenario scenario(final String id, final long delayMillis) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
        .effect(ChaosEffect.delay(Duration.ofMillis(delayMillis)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  @Test
  @DisplayName("a plan with two scenarios of one id is refused before anything is activated")
  void duplicateIdsRefused() {
    assertThatThrownBy(() -> poller("60000").applyDiff(List.of(scenario("a", 1), scenario("a", 2))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("duplicate scenario id 'a' in plan");

    verify(runtime, never()).activate(any(ChaosScenario.class));
  }

  @Test
  @DisplayName("a previous version that cannot be restored is reported as suppressed")
  void restoreFailureIsSuppressed() {
    final ChaosScenario v1 = scenario("a", 1);
    final ChaosScenario v2 = scenario("a", 2);
    when(runtime.activate(v1))
        .thenReturn(handle)
        .thenThrow(new IllegalStateException("restore refused"));
    when(runtime.activate(v2)).thenThrow(new IllegalStateException("v2 refused"));
    poller("60000").applyDiff(List.of(v1));

    assertThatThrownBy(() -> poller.applyDiff(List.of(v2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("previous scenarios kept: v2 refused")
        .cause()
        .satisfies(
            cause ->
                assertThat(cause.getSuppressed())
                    .singleElement()
                    .extracting(Throwable::getMessage)
                    .isEqualTo("restore refused"));
    verify(handle).stop();

    // Nothing is held any more: the old version is activated again from scratch.
    doReturn(handle).when(runtime).activate(v1);
    poller.applyDiff(List.of(v1));
    verify(runtime, times(3)).activate(v1);
  }

  @Test
  @DisplayName("a VirtualMachineError during a reload is logged and rethrown")
  void virtualMachineErrorRethrown() throws Exception {
    doThrow(new OutOfMemoryError("simulated exhaustion")).when(runtime).validate(any());
    poller("60000");

    final String stderr =
        stderrOf(
            () ->
                assertThatThrownBy(poller::pollOnce)
                    .isInstanceOf(OutOfMemoryError.class)
                    .hasMessage("simulated exhaustion"));

    assertThat(stderr).contains("[chaos-agent] config reload failed: simulated exhaustion");
  }

  @Test
  @DisplayName("a scenario the runtime rejects as invalid is not parsed again for the same content")
  void invalidScenarioNotRetried() throws Exception {
    doThrow(new ChaosValidationException("unsupported effect")).when(runtime).validate(any());
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(1)).validate(any());
  }

  @Test
  @DisplayName("a scenario rejected with an IllegalArgumentException is not parsed again")
  void illegalArgumentNotRetried() throws Exception {
    doThrow(new IllegalArgumentException("bad selector")).when(runtime).validate(any());
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(1)).validate(any());
  }

  @Test
  @DisplayName("an activation failure without a message is not a conflict, so it is not retried")
  void activationFailureWithoutMessageNotRetried() throws Exception {
    when(runtime.activate(any(ChaosScenario.class))).thenThrow(new ChaosActivationException(null));
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(1)).activate(any(ChaosScenario.class));
  }

  @Test
  @DisplayName("an activation failure other than a conflict is not retried for the same content")
  void activationFailureOtherThanConflictNotRetried() throws Exception {
    when(runtime.activate(any(ChaosScenario.class)))
        .thenThrow(new ChaosActivationException("stressor could not start"));
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(1)).activate(any(ChaosScenario.class));
  }

  @Test
  @DisplayName("a failure of unknown cause is retried on the next tick and logged once")
  void unknownFailureRetriedAndLoggedOnce() throws Exception {
    when(runtime.activate(any(ChaosScenario.class)))
        .thenThrow(new IllegalStateException("weaving failed"));
    poller("60000");

    final String stderr =
        stderrOf(
            () -> {
              poller.pollOnce();
              poller.pollOnce();
            });

    verify(runtime, times(2)).activate(any(ChaosScenario.class));
    assertThat(stderr.lines().filter(line -> line.contains("config reload failed")))
        .singleElement()
        .asString()
        .endsWith("weaving failed");
  }

  @Test
  @DisplayName("a conflict with a scenario active elsewhere is retried for the same content")
  void conflictRetried() throws Exception {
    when(runtime.activate(any(ChaosScenario.class)))
        .thenThrow(new ChaosActivationException("scenario 'a' is already active"));
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(2)).activate(any(ChaosScenario.class));
  }

  @Test
  @DisplayName(
      "a read whose modification time is not yet past is provisional: a same-stamp change"
          + " is seen")
  void provisionalReadSeesSameStampChange() throws Exception {
    when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle);
    // A modification time ahead of the clock (a skewed file server) counts as just written.
    final FileTime ahead = FileTime.fromMillis(System.currentTimeMillis() + 3_600_000L);
    Files.setLastModifiedTime(config, ahead);
    final StartupConfigLoader.PlanFile baseline =
        StartupConfigLoader.readPlanFile(config.toString()).orElseThrow();
    poller("3600000").startWithInitialPlan(ChaosPlanMapper.read(baseline.json()), baseline);

    Files.writeString(config, planJson("b"));
    Files.setLastModifiedTime(config, ahead);
    poller.pollOnce();

    verify(runtime, times(2)).activate(any(ChaosScenario.class));
    verify(handle).stop();
  }

  @Test
  @DisplayName(
      "a change that keeps the stamp of the applied read is not seen until the stamp moves")
  void unchangedStampSkipsTheRead() throws Exception {
    when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle);
    final StartupConfigLoader.PlanFile baseline =
        StartupConfigLoader.readPlanFile(config.toString()).orElseThrow();
    poller("3600000").startWithInitialPlan(ChaosPlanMapper.read(baseline.json()), baseline);

    // Same size, same file, same modification time: the stamp says nothing changed.
    Files.writeString(config, planJson("b"));
    Files.setLastModifiedTime(config, baseline.lastModified());
    poller.pollOnce();
    verify(runtime, times(1)).activate(any(ChaosScenario.class));

    stamp(30_000);
    poller.pollOnce();
    verify(runtime, times(2)).activate(any(ChaosScenario.class));
    verify(handle).stop();
  }

  @Test
  @DisplayName("an interrupted close keeps the interrupt and a reload it overtook applies nothing")
  void interruptedCloseWithReloadInFlight() throws Exception {
    final StartupConfigPoller racing = poller("50");
    final Thread worker;
    synchronized (racing) {
      racing.startWithInitialPlan(List.of());
      // The first tick reads plan "a" and blocks on this monitor to apply it.
      worker = awaitBlockedPollerThread();
      Thread.currentThread().interrupt();
      racing.close();
      assertThat(Thread.interrupted()).as("the interrupt survives close").isTrue();
    }
    worker.join(TimeUnit.SECONDS.toMillis(10));

    assertThat(worker.isAlive()).isFalse();
    verify(runtime, never()).activate(any(ChaosScenario.class));
  }

  private static Thread awaitBlockedPollerThread() throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      for (final Thread thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getName().equals("chaos-config-poller")
            && thread.getState() == Thread.State.BLOCKED) {
          return thread;
        }
      }
      Thread.sleep(5);
    }
    throw new AssertionError("the poller tick never reached the plan it read");
  }

  @Test
  @DisplayName("a reload that runs after close reports nothing and touches nothing")
  void reloadAfterCloseIsSilent() throws Exception {
    final StartupConfigPoller closedPoller = poller("60000");
    closedPoller.close();
    write(planJson("b"));

    final String log = stderrOf(closedPoller::pollOnce);

    assertThat(log).doesNotContain("config reloaded");
    org.mockito.Mockito.verifyNoInteractions(runtime);
  }
}
