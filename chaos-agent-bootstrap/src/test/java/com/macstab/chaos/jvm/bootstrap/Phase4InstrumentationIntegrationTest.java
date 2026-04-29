package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.BufferedReader;
import java.io.File;
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

    @Test
    @DisplayName("agent delays virtual Thread.start() (VIRTUAL_THREAD_START)")
    void agentDelaysVirtualThreadStart() throws Exception {
      // Virtual threads share the Thread.start0 hook with platform threads; the runtime
      // distinguishes them via Thread.isVirtual() and emits VIRTUAL_THREAD_START accordingly.
      // Filter the selector to ThreadKind.VIRTUAL so only virtual-thread starts match.
      final ChaosPlan plan =
          singleScenarioPlan(
              "virtual-thread-start-delay",
              ChaosSelector.thread(
                  Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "virtual-thread-start-delay"));
      assertThat(elapsed)
          .as(
              "VIRTUAL_THREAD_START delay must add ≥%d ms to virtual Thread.start() (got %d ms)",
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

    @Test
    @DisplayName("agent delays scheduled task execution (SCHEDULE_TICK)")
    void agentDelaysScheduleTick() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "schedule-tick-delay",
              ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "schedule-tick-delay"));
      assertThat(elapsed)
          .as(
              "SCHEDULE_TICK delay must add ≥%d ms to scheduled-task execution (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Executor lifecycle ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("Executor lifecycle instrumentation")
  class ExecutorLifecycleInstrumentation {

    @Test
    @DisplayName("agent delays executor.shutdown() (EXECUTOR_SHUTDOWN)")
    void agentDelaysExecutorShutdown() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-shutdown-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SHUTDOWN)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "executor-shutdown-delay"));
      assertThat(elapsed)
          .as("EXECUTOR_SHUTDOWN delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays executor.awaitTermination() (EXECUTOR_AWAIT_TERMINATION)")
    void agentDelaysExecutorAwaitTermination() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-await-termination-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "executor-await-termination-delay"));
      assertThat(elapsed)
          .as("EXECUTOR_AWAIT_TERMINATION delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── ForkJoin ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ForkJoin instrumentation")
  class ForkJoinInstrumentation {

    @Test
    @DisplayName("agent delays ForkJoinPool task execution (FORK_JOIN_TASK_RUN)")
    void agentDelaysForkJoinTaskRun() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "fork-join-delay",
              ChaosSelector.executor(Set.of(OperationType.FORK_JOIN_TASK_RUN)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "fork-join-delay"));
      assertThat(elapsed)
          .as("FORK_JOIN_TASK_RUN delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Queue remaining ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("BlockingQueue offer/poll instrumentation")
  class QueueRemainingInstrumentation {

    @Test
    @DisplayName("agent delays BlockingQueue.offer() (QUEUE_OFFER)")
    void agentDelaysQueueOffer() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-offer-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "queue-offer-delay"));
      assertThat(elapsed)
          .as("QUEUE_OFFER delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays BlockingQueue.poll() (QUEUE_POLL)")
    void agentDelaysQueuePoll() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-poll-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_POLL)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "queue-poll-delay"));
      assertThat(elapsed)
          .as("QUEUE_POLL delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Async remaining ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("CompletableFuture completeExceptionally/cancel instrumentation")
  class AsyncRemainingInstrumentation {

    @Test
    @DisplayName(
        "agent delays CompletableFuture.completeExceptionally() (ASYNC_COMPLETE_EXCEPTIONALLY)")
    void agentDelaysAsyncCompleteExceptionally() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "async-complete-exceptionally-delay",
              ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE_EXCEPTIONALLY)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "async-complete-exceptionally-delay"));
      assertThat(elapsed)
          .as(
              "ASYNC_COMPLETE_EXCEPTIONALLY delay must add ≥%d ms (got %d ms)",
              DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays CompletableFuture.cancel() (ASYNC_CANCEL)")
    void agentDelaysAsyncCancel() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "async-cancel-delay",
              ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "async-cancel-delay"));
      assertThat(elapsed)
          .as("ASYNC_CANCEL delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Clock instrumentation ──────────────────────────────────────────────────

  @Nested
  @DisplayName("Clock instrumentation")
  class ClockInstrumentation {

    private static final long CLOCK_SKEW_SECONDS = 3600L;
    private static final long CLOCK_SKEW_MIN_SECONDS = 3500L;

    @Test
    @DisplayName("agent skews Instant.now() forward (INSTANT_NOW)")
    void agentDelaysInstantNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "instant-now-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = parseSkewSeconds(runProbe(plan, "instant-now-delay"));
      assertThat(skewSeconds)
          .as(
              "INSTANT_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews LocalDateTime.now() forward (LOCAL_DATE_TIME_NOW)")
    void agentDelaysLocalDateTimeNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "local-datetime-now-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.LOCAL_DATE_TIME_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = parseSkewSeconds(runProbe(plan, "local-datetime-now-delay"));
      assertThat(skewSeconds)
          .as(
              "LOCAL_DATE_TIME_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews ZonedDateTime.now() forward (ZONED_DATE_TIME_NOW)")
    void agentDelaysZonedDateTimeNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zoned-datetime-now-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZONED_DATE_TIME_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = parseSkewSeconds(runProbe(plan, "zoned-datetime-now-delay"));
      assertThat(skewSeconds)
          .as(
              "ZONED_DATE_TIME_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews new Date() forward (DATE_NEW)")
    void agentDelaysDateNew() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "date-new-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.DATE_NEW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = parseSkewSeconds(runProbe(plan, "date-new-delay"));
      assertThat(skewSeconds)
          .as(
              "DATE_NEW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }
  }

  // ── JVM control ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("JVM control instrumentation")
  class JvmControlInstrumentation {

    @Test
    @DisplayName("agent delays System.gc() (SYSTEM_GC_REQUEST)")
    void agentDelaysSystemGc() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "system-gc-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "system-gc-delay"));
      assertThat(elapsed)
          .as("SYSTEM_GC_REQUEST delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent suppresses System.exit() — JVM continues (SYSTEM_EXIT_REQUEST)")
    void agentSuppressesSystemExit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "system-exit-suppress",
              ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)),
              ChaosEffect.suppress());
      final String output = runProbe(plan, "system-exit-suppress");
      assertThat(output)
          .as("System.exit() must be suppressed so JVM continues and prints exit=suppressed")
          .contains("exit=suppressed");
    }
  }

  // ── Direct buffer ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Direct buffer instrumentation")
  class DirectBufferInstrumentation {

    @Test
    @DisplayName("agent delays ByteBuffer.allocateDirect() (DIRECT_BUFFER_ALLOCATE)")
    void agentDelaysDirectBufferAllocate() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "direct-buffer-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.DIRECT_BUFFER_ALLOCATE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "direct-buffer-delay"));
      assertThat(elapsed)
          .as("DIRECT_BUFFER_ALLOCATE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Serialization ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Object serialization instrumentation")
  class SerializationInstrumentation {

    @Test
    @DisplayName("agent delays ObjectOutputStream.writeObject() (OBJECT_SERIALIZE)")
    void agentDelaysObjectSerialize() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "object-serialize-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_SERIALIZE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "object-serialize-delay"));
      assertThat(elapsed)
          .as("OBJECT_SERIALIZE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays ObjectInputStream.readObject() (OBJECT_DESERIALIZE)")
    void agentDelaysObjectDeserialize() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "object-deserialize-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_DESERIALIZE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "object-deserialize-delay"));
      assertThat(elapsed)
          .as("OBJECT_DESERIALIZE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Native and JNDI ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Native library and JNDI instrumentation")
  class NativeAndJndiInstrumentation {

    @Test
    @DisplayName("agent delays System.load() via NativeLibraries.load() (NATIVE_LIBRARY_LOAD)")
    void agentDelaysNativeLibLoad() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "native-lib-load-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.NATIVE_LIBRARY_LOAD)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "native-lib-load-delay"));
      assertThat(elapsed)
          .as("NATIVE_LIBRARY_LOAD delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays InitialContext.lookup() (JNDI_LOOKUP)")
    void agentDelaysJndiLookup() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jndi-lookup-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.JNDI_LOOKUP)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "jndi-lookup-delay"));
      assertThat(elapsed)
          .as("JNDI_LOOKUP delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── JMX ────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("JMX instrumentation")
  class JmxInstrumentation {

    @Test
    @DisplayName("agent delays MBeanServer.getAttribute() (JMX_GET_ATTR)")
    void agentDelaysJmxGetAttr() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jmx-get-attr-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_GET_ATTR)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "jmx-get-attr-delay"));
      assertThat(elapsed)
          .as("JMX_GET_ATTR delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays MBeanServer.invoke() (JMX_INVOKE)")
    void agentDelaysJmxInvoke() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jmx-invoke-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_INVOKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "jmx-invoke-delay"));
      assertThat(elapsed)
          .as("JMX_INVOKE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Monitor and park ───────────────────────────────────────────────────────

  @Nested
  @DisplayName("Monitor entry and thread park instrumentation")
  class MonitorAndParkInstrumentation {

    @Test
    @DisplayName("agent delays contended ReentrantLock.lock() via AQS (MONITOR_ENTER)")
    void agentDelaysMonitorEnter() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "monitor-enter-delay",
              ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "monitor-enter-delay"));
      assertThat(elapsed)
          .as("MONITOR_ENTER delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays LockSupport.parkNanos() (THREAD_PARK)")
    void agentDelaysThreadPark() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "thread-park-delay",
              ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "thread-park-delay"));
      assertThat(elapsed)
          .as("THREAD_PARK delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── ThreadLocal set ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("ThreadLocal set instrumentation")
  class ThreadLocalSetInstrumentation {

    @Test
    @DisplayName("agent delays ThreadLocal.set() (THREAD_LOCAL_SET)")
    void agentDelaysThreadLocalSet() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "thread-local-set-delay",
              ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_SET)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "thread-local-set-delay"));
      assertThat(elapsed)
          .as("THREAD_LOCAL_SET delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Shutdown lifecycle ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("Shutdown hook instrumentation")
  class ShutdownLifecycleInstrumentation {

    @Test
    @DisplayName("agent delays Runtime.addShutdownHook() (SHUTDOWN_HOOK_REGISTER)")
    void agentDelaysShutdownHookRegister() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "shutdown-hook-delay",
              ChaosSelector.shutdown(Set.of(OperationType.SHUTDOWN_HOOK_REGISTER)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "shutdown-hook-delay"));
      assertThat(elapsed)
          .as("SHUTDOWN_HOOK_REGISTER delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Class loading ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Class loading instrumentation")
  class ClassLoadingInstrumentation {

    @Test
    @DisplayName("agent delays ClassLoader.loadClass() (CLASS_LOAD)")
    void agentDelaysClassLoad() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "class-load-delay",
              ChaosSelector.classLoading(
                  Set.of(OperationType.CLASS_LOAD), NamePattern.regex(".*AgentProcessProbeMain")),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "class-load-delay"));
      assertThat(elapsed)
          .as("CLASS_LOAD delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays ClassLoader.defineClass() (CLASS_DEFINE)")
    void agentDelaysClassDefine() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "class-define-delay",
              ChaosSelector.classLoading(
                  Set.of(OperationType.CLASS_DEFINE), NamePattern.regex(".*AgentProcessProbeMain")),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "class-define-delay"));
      assertThat(elapsed)
          .as("CLASS_DEFINE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays ClassLoader.getResource() (RESOURCE_LOAD)")
    void agentDelaysResourceLoad() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "resource-load-delay",
              ChaosSelector.classLoading(
                  Set.of(OperationType.RESOURCE_LOAD), NamePattern.regex("probe-chaos-resource.*")),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "resource-load-delay"));
      assertThat(elapsed)
          .as("RESOURCE_LOAD delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── NIO remaining ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("NIO selector and channel connect/accept instrumentation")
  class NioRemainingInstrumentation {

    @Test
    @DisplayName("agent delays Selector.select(timeout) (NIO_SELECTOR_SELECT)")
    void agentDelaysNioSelectorSelect() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-selector-select-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "nio-selector-select-delay"));
      assertThat(elapsed)
          .as("NIO_SELECTOR_SELECT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays SocketChannel.connect() (NIO_CHANNEL_CONNECT)")
    void agentDelaysNioChannelConnect() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-channel-connect-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_CONNECT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "nio-channel-connect-delay"));
      assertThat(elapsed)
          .as("NIO_CHANNEL_CONNECT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays ServerSocketChannel.accept() (NIO_CHANNEL_ACCEPT)")
    void agentDelaysNioChannelAccept() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-channel-accept-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_ACCEPT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "nio-channel-accept-delay"));
      assertThat(elapsed)
          .as("NIO_CHANNEL_ACCEPT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── Socket ─────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("Socket instrumentation")
  class SocketInstrumentation {

    @Test
    @DisplayName("agent delays Socket.connect() (SOCKET_CONNECT)")
    void agentDelaysSocketConnect() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-connect-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "socket-connect-delay"));
      assertThat(elapsed)
          .as("SOCKET_CONNECT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays ServerSocket.accept() (SOCKET_ACCEPT)")
    void agentDelaysSocketAccept() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-accept-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "socket-accept-delay"));
      assertThat(elapsed)
          .as("SOCKET_ACCEPT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays SocketInputStream.read() (SOCKET_READ)")
    void agentDelaysSocketRead() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-read-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_READ)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "socket-read-delay"));
      assertThat(elapsed)
          .as("SOCKET_READ delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays SocketOutputStream.write() (SOCKET_WRITE)")
    void agentDelaysSocketWrite() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-write-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "socket-write-delay"));
      assertThat(elapsed)
          .as("SOCKET_WRITE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Socket.close() (SOCKET_CLOSE)")
    void agentDelaysSocketClose() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-close-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "socket-close-delay"));
      assertThat(elapsed)
          .as("SOCKET_CLOSE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── HTTP client ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("HTTP client instrumentation")
  class HttpClientInstrumentation {

    @Test
    @DisplayName("agent delays HttpClient.send() (HTTP_CLIENT_SEND)")
    void agentDelaysHttpClientSend() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "http-client-send-delay",
              ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "http-client-send-delay"));
      assertThat(elapsed)
          .as("HTTP_CLIENT_SEND delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays HttpClient.sendAsync() (HTTP_CLIENT_SEND_ASYNC)")
    void agentDelaysHttpClientSendAsync() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "http-client-send-async-delay",
              ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runProbe(plan, "http-client-send-async-delay"));
      assertThat(elapsed)
          .as("HTTP_CLIENT_SEND_ASYNC delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  // ── JDBC ────────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("JDBC instrumentation")
  class JdbcInstrumentation {

    @Test
    @DisplayName("agent delays Statement.execute() (JDBC_STATEMENT_EXECUTE)")
    void agentDelaysJdbcStatementExecute() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-statement-delay",
              ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runJdbcProbe(plan, "jdbc-statement-delay"));
      assertThat(elapsed)
          .as("JDBC_STATEMENT_EXECUTE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Connection.prepareStatement() (JDBC_PREPARED_STATEMENT)")
    void agentDelaysJdbcPreparedStatement() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-prepared-statement-delay",
              ChaosSelector.jdbc(OperationType.JDBC_PREPARED_STATEMENT),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runJdbcProbe(plan, "jdbc-prepared-statement-delay"));
      assertThat(elapsed)
          .as("JDBC_PREPARED_STATEMENT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Connection.commit() (JDBC_TRANSACTION_COMMIT)")
    void agentDelaysJdbcCommit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-commit-delay",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_COMMIT),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runJdbcProbe(plan, "jdbc-commit-delay"));
      assertThat(elapsed)
          .as("JDBC_TRANSACTION_COMMIT delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays Connection.rollback() (JDBC_TRANSACTION_ROLLBACK)")
    void agentDelaysJdbcRollback() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-rollback-delay",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_ROLLBACK),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runJdbcProbe(plan, "jdbc-rollback-delay"));
      assertThat(elapsed)
          .as("JDBC_TRANSACTION_ROLLBACK delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    @Test
    @DisplayName("agent delays HikariPool.getConnection() (JDBC_CONNECTION_ACQUIRE)")
    void agentDelaysJdbcConnectionAcquire() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-connection-acquire-delay",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      final long elapsed = parseElapsed(runJdbcProbe(plan, "jdbc-connection-acquire-delay"));
      assertThat(elapsed)
          .as("JDBC_CONNECTION_ACQUIRE delay must add ≥%d ms (got %d ms)", DELAY_MIN_MS, elapsed)
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
    return runProbeInternal(plan, mode, null, extraJvmArgs);
  }

  /** Runs a JDBC probe with H2 and HikariCP added to the child JVM classpath. */
  private String runJdbcProbe(final ChaosPlan plan, final String mode) throws Exception {
    return runProbeInternal(plan, mode, System.getProperty("chaos.test.jdbcClasspath"));
  }

  private String runProbeInternal(
      final ChaosPlan plan,
      final String mode,
      final String extraClasspath,
      final String... extraJvmArgs)
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
    final String baseCp = System.getProperty("java.class.path");
    cmd.add(
        (extraClasspath != null && !extraClasspath.isEmpty())
            ? baseCp + File.pathSeparator + extraClasspath
            : baseCp);
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

  private static long parseSkewSeconds(final String output) {
    final int idx = output.indexOf("skewSeconds=");
    assertThat(idx).as("output must contain skewSeconds=: " + output).isNotNegative();
    return Long.parseLong(output.substring(idx + "skewSeconds=".length()).trim());
  }
}
