package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end instrumentation tests that launch a child JVM with {@code -javaagent:} and verify that
 * Phase 4 ByteBuddy advice actually intercepts {@link Thread#sleep}, file I/O, and DNS resolution
 * through the full instrumentation pipeline.
 *
 * <p>Each test writes a {@link ChaosPlan} to a temp file, forks a JVM that loads the agent, runs a
 * targeted probe action, and asserts on the observed timing or behaviour.
 */
@DisplayName("Phase 4 bytecode instrumentation (end-to-end)")
class Phase4InstrumentationIntegrationTest {

  private static final long DELAY_MS = 200L;
  private static final long DELAY_MIN_MS = (long) (DELAY_MS * 0.8);

  @TempDir Path tempDir;

  // ── Thread.sleep ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Thread.sleep instrumentation")
  class ThreadSleepInstrumentation {

    @Test
    @DisplayName("agent suppresses Thread.sleep — returns in under 300 ms despite 2 s sleep call")
    void agentSuppressesThreadSleep() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "sleep-suppress",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.suppress());

      final long elapsed = parseElapsed(runProbe(plan, "sleep-suppress"));
      assertThat(elapsed)
          .as("suppressed Thread.sleep(2000) should return in <300 ms (got %d ms)", elapsed)
          .isLessThan(300L);
    }

    @Test
    @DisplayName("agent delays Thread.sleep by configured amount")
    void agentDelaysThreadSleep() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "sleep-delay",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed = parseElapsed(runProbe(plan, "sleep-delay"));
      assertThat(elapsed)
          .as(
              "delay effect must add ≥%d ms before Thread.sleep(10) (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── File I/O ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("File I/O instrumentation")
  class FileIoInstrumentation {

    @Test
    @DisplayName("agent delays FileInputStream.read()")
    void agentDelaysFileRead() throws Exception {
      final Path probeFile = tempDir.resolve("probe-read.dat");
      Files.write(probeFile, new byte[] {1, 2, 3});

      final ChaosPlan plan =
          singleScenarioPlan(
              "fileread-delay",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed =
          parseElapsed(
              runProbe(plan, "fileread-delay", "-Dprobe.file=" + probeFile.toAbsolutePath()));
      assertThat(elapsed)
          .as(
              "delay effect must add ≥%d ms before FileInputStream.read() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays FileOutputStream.write()")
    void agentDelaysFileWrite() throws Exception {
      final Path probeFile = tempDir.resolve("probe-write.dat");

      final ChaosPlan plan =
          singleScenarioPlan(
              "filewrite-delay",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed =
          parseElapsed(
              runProbe(plan, "filewrite-delay", "-Dprobe.file=" + probeFile.toAbsolutePath()));
      assertThat(elapsed)
          .as(
              "delay effect must add ≥%d ms before FileOutputStream.write() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("FILE_IO_READ selector does not delay FILE_IO_WRITE")
    void readSelectorDoesNotDelayWrite() throws Exception {
      final Path probeFile = tempDir.resolve("probe-write-isolation.dat");

      final ChaosPlan plan =
          singleScenarioPlan(
              "filewrite-isolation",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed =
          parseElapsed(
              runProbe(plan, "filewrite-delay", "-Dprobe.file=" + probeFile.toAbsolutePath()));
      assertThat(elapsed)
          .as("READ-only selector must NOT delay write (got %d ms)", elapsed)
          .isLessThan(DELAY_MIN_MS);
    }
  }

  // ── DNS ──────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("DNS instrumentation")
  class DnsInstrumentation {

    @Test
    @DisplayName("agent delays InetAddress.getByName()")
    void agentDelaysDnsResolve() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "dns-delay",
              ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed = parseElapsed(runProbe(plan, "dns-delay"));
      assertThat(elapsed)
          .as(
              "delay effect must add ≥%d ms before InetAddress.getByName() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── SSL/TLS ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("SSL/TLS instrumentation")
  class SslInstrumentation {

    @Test
    @DisplayName("agent delays SSLEngine.beginHandshake()")
    void agentDelaysSslEngineBeginHandshake() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "ssl-delay",
              ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));

      final long elapsed = parseElapsed(runProbe(plan, "ssl-delay"));
      assertThat(elapsed)
          .as(
              "delay effect must add ≥%d ms before SSLEngine.beginHandshake() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Executor ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Executor instrumentation")
  class ExecutorInstrumentation {

    @Test
    @DisplayName("agent delays executor.execute() at submission (EXECUTOR_SUBMIT)")
    void agentDelaysExecutorSubmit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-submit-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "executor-submit-delay"));
      assertThat(elapsed)
          .as(
              "EXECUTOR_SUBMIT delay must add ≥%d ms to execute() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays task execution in worker thread (EXECUTOR_WORKER_RUN)")
    void agentDelaysExecutorWorkerRun() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-worker-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "executor-worker-delay"));
      assertThat(elapsed)
          .as(
              "EXECUTOR_WORKER_RUN delay must add ≥%d ms to task execution (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── BlockingQueue ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("BlockingQueue instrumentation")
  class QueueInstrumentation {

    @Test
    @DisplayName("agent delays BlockingQueue.put() (QUEUE_PUT)")
    void agentDelaysQueuePut() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-put-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "queue-put-delay"));
      assertThat(elapsed)
          .as("QUEUE_PUT delay must add ≥%d ms to put() (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays BlockingQueue.take() (QUEUE_TAKE)")
    void agentDelaysQueueTake() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-take-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_TAKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "queue-take-delay"));
      assertThat(elapsed)
          .as("QUEUE_TAKE delay must add ≥%d ms to take() (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── CompletableFuture ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("CompletableFuture instrumentation")
  class AsyncInstrumentation {

    @Test
    @DisplayName("agent delays CompletableFuture.complete() (ASYNC_COMPLETE)")
    void agentDelaysAsyncComplete() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "async-complete-delay",
              ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "async-complete-delay"));
      assertThat(elapsed)
          .as(
              "ASYNC_COMPLETE delay must add ≥%d ms to complete() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── JVM runtime ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("JVM runtime instrumentation")
  class JvmRuntimeInstrumentation {

    @Test
    @DisplayName("agent delays Method.invoke() (REFLECTION_INVOKE)")
    void agentDelaysReflectionInvoke() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "reflection-invoke-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "reflection-invoke-delay"));
      assertThat(elapsed)
          .as(
              "REFLECTION_INVOKE delay must add ≥%d ms to Method.invoke() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Deflater.deflate() (ZIP_DEFLATE)")
    void agentDelaysZipDeflate() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zip-deflate-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_DEFLATE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "zip-deflate-delay"));
      assertThat(elapsed)
          .as(
              "ZIP_DEFLATE delay must add ≥%d ms to Deflater.deflate() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Inflater.inflate() (ZIP_INFLATE)")
    void agentDelaysZipInflate() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zip-inflate-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_INFLATE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "zip-inflate-delay"));
      assertThat(elapsed)
          .as(
              "ZIP_INFLATE delay must add ≥%d ms to Inflater.inflate() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Thread and monitor ────────────────────────────────────────────────────

  @Nested
  @DisplayName("Thread and monitor instrumentation")
  class ThreadingInstrumentation {

    @Test
    @DisplayName("agent delays Thread.start() (THREAD_START)")
    void agentDelaysThreadStart() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "thread-start-delay",
              ChaosSelector.thread(
                  Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "thread-start-delay"));
      assertThat(elapsed)
          .as(
              "THREAD_START delay must add ≥%d ms to Thread.start() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

  }

  // ── NIO channels ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("NIO channel instrumentation")
  class NioInstrumentation {

    @Test
    @DisplayName("agent delays NIO channel read (NIO_CHANNEL_READ)")
    void agentDelaysNioChannelRead() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-read-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_READ)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "nio-read-delay"));
      assertThat(elapsed)
          .as(
              "NIO_CHANNEL_READ delay must add ≥%d ms to channel.read() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays NIO channel write (NIO_CHANNEL_WRITE)")
    void agentDelaysNioChannelWrite() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-write-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "nio-write-delay"));
      assertThat(elapsed)
          .as(
              "NIO_CHANNEL_WRITE delay must add ≥%d ms to channel.write() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── ThreadLocal ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ThreadLocal instrumentation")
  class ThreadLocalInstrumentation {

    @Test
    @DisplayName("agent delays ThreadLocal.get() (THREAD_LOCAL_GET)")
    void agentDelaysThreadLocalGet() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "thread-local-get-delay",
              ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "thread-local-get-delay"));
      assertThat(elapsed)
          .as(
              "THREAD_LOCAL_GET delay must add ≥%d ms to ThreadLocal.get() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Scheduling ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Scheduling instrumentation")
  class SchedulingInstrumentation {

    @Test
    @DisplayName("agent delays ScheduledExecutorService.schedule() (SCHEDULE_SUBMIT)")
    void agentDelaysScheduleSubmit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "schedule-submit-delay",
              ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "schedule-submit-delay"));
      assertThat(elapsed)
          .as(
              "SCHEDULE_SUBMIT delay must add ≥%d ms to schedule() (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private ChaosPlan singleScenarioPlan(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return new ChaosPlan(
        new ChaosPlan.Metadata(id, ""),
        null,
        List.of(
            ChaosScenario.builder(id)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(selector)
                .effect(effect)
                .activationPolicy(ActivationPolicy.always())
                .build()));
  }

  private String runProbe(final ChaosPlan plan, final String mode, final String... extraJvmArgs)
      throws Exception {
    final Path configFile = tempDir.resolve(mode + "-plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(plan));
    final String agentJar = System.getProperty("chaos.bootstrap.agentJar");

    final List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-javaagent:" + agentJar + "=configFile=" + configFile);
    for (final String arg : extraJvmArgs) {
      cmd.add(arg);
    }
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(AgentProcessProbeMain.class.getName());
    cmd.add(mode);

    final Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    assertThat(finished).as("probe JVM should finish within 30 s").isTrue();
    try (final BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      final String output = reader.lines().reduce("", (a, b) -> a + b);
      assertThat(process.exitValue()).as("probe JVM exit value: " + output).isZero();
      return output;
    }
  }

  private static long parseElapsed(final String output) {
    final int idx = output.indexOf("elapsedMillis=");
    assertThat(idx).as("output must contain elapsedMillis=: " + output).isNotNegative();
    return Long.parseLong(output.substring(idx + "elapsedMillis=".length()).trim());
  }
}
