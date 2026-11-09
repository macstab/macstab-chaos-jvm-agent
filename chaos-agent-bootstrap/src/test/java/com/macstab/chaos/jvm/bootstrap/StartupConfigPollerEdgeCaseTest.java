package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationConflictException;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * How the poller handles plans and runtimes that fail, change detection at the edges of the file
 * stamp, and a close that races a reload.
 */
@DisplayName("StartupConfigPoller edge cases")
@ResourceLock("System.err")
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
                AgentSettings.of("configWatchInterval=" + interval, Map.of()), config, runtime)
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

  private static ChaosScenario scenario(final String id, final long delayMillis) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
        .effect(ChaosEffect.delay(Duration.ofMillis(delayMillis)))
        .activationPolicy(ActivationPolicy.always())
        .build();
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
  @DisplayName("a VirtualMachineError during a reload is logged, not rethrown: watching goes on")
  void virtualMachineErrorLogged() throws Exception {
    doThrow(new OutOfMemoryError("simulated exhaustion")).when(runtime).validate(any());
    poller("60000");

    final String stderr =
        StderrCapture.of(() -> assertThatCode(poller::pollOnce).doesNotThrowAnyException());

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
        StderrCapture.of(
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
        .thenThrow(new ChaosActivationConflictException("scenario 'a' is already active"));
    poller("60000").pollOnce();
    stamp(30_000);
    poller.pollOnce();

    verify(runtime, times(2)).activate(any(ChaosScenario.class));
  }

  /** Starts the poller from a baseline read of the plan, as the agent does. */
  private void startFromBaseline(final StartupConfigPoller started) {
    final StartupConfigLoader.PlanFile baseline =
        StartupConfigLoader.readPlanFile(config.toString()).orElseThrow();
    started.startWithInitialPlan(ChaosPlanMapper.read(baseline.json()), baseline);
  }

  @Test
  @DisplayName(
      "a modification time ahead of the clock is read again only within 20 ms of its first sight")
  void futureModificationTimeIsNotReadOnEveryTick() throws Exception {
    when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle);
    // A skewed file server: an hour ahead, with a millisecond part (a whole second would get the
    // window of a file system that stores whole seconds).
    long aheadMillis = System.currentTimeMillis() + 3_600_000L;
    if (aheadMillis % 1_000L == 0L) {
      aheadMillis++;
    }
    final FileTime ahead = FileTime.fromMillis(aheadMillis);
    Files.setLastModifiedTime(config, ahead);
    final java.util.concurrent.atomic.AtomicInteger digested =
        new java.util.concurrent.atomic.AtomicInteger();
    poller = new StartupConfigPoller(config, runtime, 3_600_000L, digested::incrementAndGet);
    startFromBaseline(poller);
    Thread.sleep(50);

    // Same size, same stamp: past the window, the poller does not read the file again.
    Files.writeString(config, planJson("b"));
    Files.setLastModifiedTime(config, ahead);
    poller.pollOnce();
    poller.pollOnce();

    assertThat(digested).as("reads of new content after the window").hasValue(0);
    verify(runtime, times(1)).activate(any(ChaosScenario.class));
  }

  @Test
  @DisplayName("a same-size edit within the same whole second is applied")
  void sameSecondEditIsApplied() throws Exception {
    when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle);
    // Pin the clock to 20..300 ms past a whole second T: a read then is not within 20 ms of T,
    // and the next tick still falls within T + 1 s.
    while (System.currentTimeMillis() % 1_000L < 20L
        || System.currentTimeMillis() % 1_000L >= 300L) {
      Thread.sleep(5);
    }
    final long now = System.currentTimeMillis();
    final FileTime wholeSecond = FileTime.fromMillis(now - now % 1_000L);
    Files.setLastModifiedTime(config, wholeSecond);
    poller("3600000").pollOnce();
    verify(runtime, times(1)).activate(any(ChaosScenario.class));

    // A file system storing whole seconds gives the edit the same stamp.
    Files.writeString(config, planJson("b"));
    Files.setLastModifiedTime(config, wholeSecond);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.currentTimeMillis() < wholeSecond.toMillis() + 900L, "the tick ran late");
    poller.pollOnce();

    verify(runtime, times(2)).activate(any(ChaosScenario.class));
    verify(handle).stop();
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs({
    org.junit.jupiter.api.condition.OS.LINUX,
    org.junit.jupiter.api.condition.OS.MAC
  })
  @DisplayName("an unreadable plan with an unchanged stamp is not read, so no read fails")
  void unreadableUnchangedPlanIsNotRead() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        "root".equals(System.getProperty("user.name")), "root reads a file of mode 000");
    when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle);
    startFromBaseline(poller("3600000"));
    final Set<java.nio.file.attribute.PosixFilePermission> mode =
        Files.getPosixFilePermissions(config);
    // chmod changes the change time only, not the modification time.
    Files.setPosixFilePermissions(config, Set.of());
    final String stderr;
    try {
      stderr =
          StderrCapture.of(
              () -> {
                poller.pollOnce();
                poller.pollOnce();
              });
    } finally {
      Files.setPosixFilePermissions(config, mode);
    }

    assertThat(stderr).doesNotContain("config reload failed");
    verify(runtime, times(1)).activate(any(ChaosScenario.class));
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
  @org.junit.jupiter.api.condition.EnabledOnOs({
    org.junit.jupiter.api.condition.OS.LINUX,
    org.junit.jupiter.api.condition.OS.MAC
  })
  @DisplayName("a plan refused for good is not read again while its stamp is unchanged")
  void refusedPlanIsNotReadAgain() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        "root".equals(System.getProperty("user.name")), "root reads a file of mode 000");
    write("{ broken");
    poller("3600000");
    final String firstTick = StderrCapture.of(poller::pollOnce);
    final Set<java.nio.file.attribute.PosixFilePermission> mode =
        Files.getPosixFilePermissions(config);
    Files.setPosixFilePermissions(config, Set.of());
    final String secondTick;
    try {
      secondTick = StderrCapture.of(poller::pollOnce);
    } finally {
      Files.setPosixFilePermissions(config, mode);
    }

    assertThat(firstTick).contains("config reload failed");
    assertThat(secondTick).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(runtime);
  }

  @Test
  @DisplayName("a reload that runs after close reports nothing and touches nothing")
  void reloadAfterCloseIsSilent() throws Exception {
    final StartupConfigPoller closedPoller = poller("60000");
    closedPoller.close();
    write(planJson("b"));

    final String log = StderrCapture.of(closedPoller::pollOnce);

    assertThat(log).doesNotContain("config reloaded");
    org.mockito.Mockito.verifyNoInteractions(runtime);
  }
}
