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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end instrumentation tests that launch a child JVM with {@code -javaagent:} and verify that
 * the ByteBuddy advice actually intercepts {@link Thread#sleep}, file I/O, and DNS resolution
 * through the full instrumentation pipeline.
 *
 * <p>Each test writes a {@link ChaosPlan} to a temp file, forks a JVM that loads the agent, runs a
 * targeted probe action, and asserts on the observed timing or behaviour.
 */
@DisplayName("Bytecode instrumentation (end-to-end)")
class InstrumentationIntegrationTest {

  private static final long DELAY_MS = 200L;

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

      final ChildJvmProbe.ProbeResult result = runProbe(plan, "sleep-suppress");
      assertThat(result.longValue("applied.sleep-suppress")).isPositive();
      assertThat(result.longValue("elapsedMillis"))
          .as("suppressed Thread.sleep(2000) should return in <300 ms")
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

      assertDelayed(plan, runProbe(plan, "sleep-delay"));
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

      assertDelayed(
          plan, runProbe(plan, "fileread-delay", "-Dprobe.file=" + probeFile.toAbsolutePath()));
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

      assertDelayed(
          plan, runProbe(plan, "filewrite-delay", "-Dprobe.file=" + probeFile.toAbsolutePath()));
    }

    @Test
    @DisplayName("FILE_IO_READ selector does not delay FILE_IO_WRITE")
    void readSelectorDoesNotDelayWrite() throws Exception {
      final Path probeFile = tempDir.resolve("probe-write-isolation.dat");

      final ChaosPlan plan =
          singleScenarioPlan(
              "filewrite-isolation",
              ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
              ChaosEffect.delay(Duration.ofSeconds(2)));

      final ChildJvmProbe.ProbeResult result =
          runProbe(plan, "filewrite-delay", "-Dprobe.file=" + probeFile.toAbsolutePath());
      // The scenario applies to the JVM's own file reads, so its count is not 0; the write is
      // not delayed: a delayed call takes at least the whole 2 s delay.
      assertThat(result.longValue("elapsedMillis"))
          .as("READ-only selector must NOT delay write")
          .isLessThan(1_000L);
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

      assertDelayed(plan, runProbe(plan, "dns-delay"));
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

      assertDelayed(plan, runProbe(plan, "ssl-delay"));
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
      assertDelayed(plan, runProbe(plan, "executor-submit-delay"));
    }

    @Test
    @DisplayName("agent delays task execution in worker thread (EXECUTOR_WORKER_RUN)")
    void agentDelaysExecutorWorkerRun() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-worker-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "executor-worker-delay"));
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
      assertDelayed(plan, runProbe(plan, "queue-put-delay"));
    }

    @Test
    @DisplayName("agent delays BlockingQueue.take() (QUEUE_TAKE)")
    void agentDelaysQueueTake() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-take-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_TAKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "queue-take-delay"));
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
      assertDelayed(plan, runProbe(plan, "async-complete-delay"));
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
      assertDelayed(plan, runProbe(plan, "reflection-invoke-delay"));
    }

    @Test
    @DisplayName("agent delays Deflater.deflate() (ZIP_DEFLATE)")
    void agentDelaysZipDeflate() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zip-deflate-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_DEFLATE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "zip-deflate-delay"));
    }

    @Test
    @DisplayName("agent delays Inflater.inflate() (ZIP_INFLATE)")
    void agentDelaysZipInflate() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zip-inflate-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_INFLATE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "zip-inflate-delay"));
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
      assertDelayed(plan, runProbe(plan, "thread-start-delay"));
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
      assertDelayed(plan, runProbe(plan, "virtual-thread-start-delay"));
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
      assertDelayed(plan, runProbe(plan, "nio-read-delay"));
    }

    @Test
    @DisplayName("agent delays NIO channel write (NIO_CHANNEL_WRITE)")
    void agentDelaysNioChannelWrite() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-write-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "nio-write-delay"));
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
      assertDelayed(plan, runProbe(plan, "thread-local-get-delay"));
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
      assertDelayed(plan, runProbe(plan, "schedule-submit-delay"));
    }

    @Test
    @DisplayName("agent delays scheduled task execution (SCHEDULE_TICK)")
    void agentDelaysScheduleTick() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "schedule-tick-delay",
              ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "schedule-tick-delay"));
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
      assertDelayed(plan, runProbe(plan, "executor-shutdown-delay"));
    }

    @Test
    @DisplayName("agent delays executor.awaitTermination() (EXECUTOR_AWAIT_TERMINATION)")
    void agentDelaysExecutorAwaitTermination() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "executor-await-termination-delay",
              ChaosSelector.executor(Set.of(OperationType.EXECUTOR_AWAIT_TERMINATION)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "executor-await-termination-delay"));
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
      assertDelayed(plan, runProbe(plan, "fork-join-delay"));
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
      assertDelayed(plan, runProbe(plan, "queue-offer-delay"));
    }

    @Test
    @DisplayName("agent delays BlockingQueue.poll() (QUEUE_POLL)")
    void agentDelaysQueuePoll() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "queue-poll-delay",
              ChaosSelector.queue(Set.of(OperationType.QUEUE_POLL)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "queue-poll-delay"));
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
      assertDelayed(plan, runProbe(plan, "async-complete-exceptionally-delay"));
    }

    @Test
    @DisplayName("agent delays CompletableFuture.cancel() (ASYNC_CANCEL)")
    void agentDelaysAsyncCancel() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "async-cancel-delay",
              ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "async-cancel-delay"));
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
    void agentSkewsInstantNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "instant-now-skew",
              ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = runProbe(plan, "instant-now-skew").longValue("skewSeconds");
      assertThat(skewSeconds)
          .as(
              "INSTANT_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews LocalDateTime.now() forward (LOCAL_DATE_TIME_NOW)")
    void agentSkewsLocalDateTimeNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "local-datetime-now-skew",
              ChaosSelector.jvmRuntime(Set.of(OperationType.LOCAL_DATE_TIME_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = runProbe(plan, "local-datetime-now-skew").longValue("skewSeconds");
      assertThat(skewSeconds)
          .as(
              "LOCAL_DATE_TIME_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews ZonedDateTime.now() forward (ZONED_DATE_TIME_NOW)")
    void agentSkewsZonedDateTimeNow() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "zoned-datetime-now-skew",
              ChaosSelector.jvmRuntime(Set.of(OperationType.ZONED_DATE_TIME_NOW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = runProbe(plan, "zoned-datetime-now-skew").longValue("skewSeconds");
      assertThat(skewSeconds)
          .as(
              "ZONED_DATE_TIME_NOW skew must shift returned value by ≥%d s (got %d s)",
              CLOCK_SKEW_MIN_SECONDS, skewSeconds)
          .isGreaterThanOrEqualTo(CLOCK_SKEW_MIN_SECONDS);
    }

    @Test
    @DisplayName("agent skews new Date() forward (DATE_NEW)")
    void agentSkewsDateNew() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "date-new-skew",
              ChaosSelector.jvmRuntime(Set.of(OperationType.DATE_NEW)),
              ChaosEffect.skewClock(
                  java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS),
                  ChaosEffect.ClockSkewMode.FIXED));
      final long skewSeconds = runProbe(plan, "date-new-skew").longValue("skewSeconds");
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
      assertDelayed(plan, runProbe(plan, "system-gc-delay"));
    }

    @Test
    @DisplayName("agent suppresses System.exit() — JVM continues (SYSTEM_EXIT_REQUEST)")
    void agentSuppressesSystemExit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "system-exit-suppress",
              ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)),
              ChaosEffect.suppress());
      final String output = runProbe(plan, "system-exit-suppress").output();
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
      assertDelayed(plan, runProbe(plan, "direct-buffer-delay"));
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
      assertDelayed(plan, runProbe(plan, "object-serialize-delay"));
    }

    @Test
    @DisplayName("agent delays ObjectInputStream.readObject() (OBJECT_DESERIALIZE)")
    void agentDelaysObjectDeserialize() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "object-deserialize-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_DESERIALIZE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "object-deserialize-delay"));
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
      assertDelayed(plan, runProbe(plan, "native-lib-load-delay"));
    }

    @Test
    @DisplayName("agent delays InitialContext.lookup() (JNDI_LOOKUP)")
    void agentDelaysJndiLookup() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jndi-lookup-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.JNDI_LOOKUP)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "jndi-lookup-delay"));
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
      assertDelayed(plan, runProbe(plan, "jmx-get-attr-delay"));
    }

    @Test
    @DisplayName("agent delays MBeanServer.invoke() (JMX_INVOKE)")
    void agentDelaysJmxInvoke() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jmx-invoke-delay",
              ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_INVOKE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "jmx-invoke-delay"));
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
      assertDelayed(plan, runProbe(plan, "monitor-enter-delay"));
    }

    @Test
    @DisplayName("agent delays LockSupport.parkNanos() (THREAD_PARK)")
    void agentDelaysThreadPark() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "thread-park-delay",
              ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "thread-park-delay"));
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
      assertDelayed(plan, runProbe(plan, "thread-local-set-delay"));
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
      assertDelayed(plan, runProbe(plan, "shutdown-hook-delay"));
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
      assertDelayed(plan, runProbe(plan, "class-load-delay"));
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
      assertDelayed(plan, runProbe(plan, "class-define-delay"));
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
      assertDelayed(plan, runProbe(plan, "resource-load-delay"));
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
      assertDelayed(plan, runProbe(plan, "nio-selector-select-delay"));
    }

    @Test
    @DisplayName("agent delays SocketChannel.connect() (NIO_CHANNEL_CONNECT)")
    void agentDelaysNioChannelConnect() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-channel-connect-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_CONNECT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "nio-channel-connect-delay"));
    }

    @Test
    @DisplayName("agent delays ServerSocketChannel.accept() (NIO_CHANNEL_ACCEPT)")
    void agentDelaysNioChannelAccept() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "nio-channel-accept-delay",
              ChaosSelector.nio(Set.of(OperationType.NIO_CHANNEL_ACCEPT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "nio-channel-accept-delay"));
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
      assertDelayed(plan, runProbe(plan, "socket-connect-delay"));
    }

    @Test
    @DisplayName("agent delays ServerSocket.accept() (SOCKET_ACCEPT)")
    void agentDelaysSocketAccept() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-accept-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_ACCEPT)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "socket-accept-delay"));
    }

    @Test
    @DisplayName("agent delays SocketInputStream.read() (SOCKET_READ)")
    void agentDelaysSocketRead() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-read-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_READ)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "socket-read-delay"));
    }

    @Test
    @DisplayName("agent delays SocketOutputStream.write() (SOCKET_WRITE)")
    void agentDelaysSocketWrite() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-write-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "socket-write-delay"));
    }

    @Test
    @DisplayName("agent delays Socket.close() (SOCKET_CLOSE)")
    void agentDelaysSocketClose() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "socket-close-delay",
              ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "socket-close-delay"));
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
      assertDelayed(plan, runProbe(plan, "http-client-send-delay"));
    }

    @Test
    @DisplayName("agent delays HttpClient.sendAsync() (HTTP_CLIENT_SEND_ASYNC)")
    void agentDelaysHttpClientSendAsync() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "http-client-send-async-delay",
              ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC)),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runProbe(plan, "http-client-send-async-delay"));
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
      assertDelayed(plan, runJdbcProbe(plan, "jdbc-statement-delay"));
    }

    @Test
    @DisplayName("agent delays Connection.prepareStatement() (JDBC_PREPARED_STATEMENT)")
    void agentDelaysJdbcPreparedStatement() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-prepared-statement-delay",
              ChaosSelector.jdbc(OperationType.JDBC_PREPARED_STATEMENT),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runJdbcProbe(plan, "jdbc-prepared-statement-delay"));
    }

    @Test
    @DisplayName("agent delays Connection.commit() (JDBC_TRANSACTION_COMMIT)")
    void agentDelaysJdbcCommit() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-commit-delay",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_COMMIT),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runJdbcProbe(plan, "jdbc-commit-delay"));
    }

    @Test
    @DisplayName("agent delays Connection.rollback() (JDBC_TRANSACTION_ROLLBACK)")
    void agentDelaysJdbcRollback() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-rollback-delay",
              ChaosSelector.jdbc(OperationType.JDBC_TRANSACTION_ROLLBACK),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runJdbcProbe(plan, "jdbc-rollback-delay"));
    }

    @Test
    @DisplayName("agent delays HikariPool.getConnection() (JDBC_CONNECTION_ACQUIRE)")
    void agentDelaysJdbcConnectionAcquire() throws Exception {
      final ChaosPlan plan =
          singleScenarioPlan(
              "jdbc-connection-acquire-delay",
              ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE),
              ChaosEffect.delay(Duration.ofMillis(DELAY_MS)));
      assertDelayed(plan, runJdbcProbe(plan, "jdbc-connection-acquire-delay"));
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

  private ChildJvmProbe.ProbeResult runProbe(
      final ChaosPlan plan, final String mode, final String... extraJvmArgs) throws Exception {
    return runProbeInternal(plan, mode, null, extraJvmArgs);
  }

  /** Runs a JDBC probe with H2 and HikariCP added to the child JVM classpath. */
  private ChildJvmProbe.ProbeResult runJdbcProbe(final ChaosPlan plan, final String mode)
      throws Exception {
    return runProbeInternal(plan, mode, System.getProperty("chaos.test.jdbcClasspath"));
  }

  private ChildJvmProbe.ProbeResult runProbeInternal(
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

    // No coverage agent here: these plans reach every file write, thread start, class load, gc and
    // exit of the JVM, the coverage agent's own dump at shutdown included.
    final ChildJvmProbe.BoundedRun run =
        ChildJvmProbe.runBounded(cmd, tempDir.resolve(mode + ".out"), Duration.ofSeconds(30));
    assertThat(run.finished())
        .as(
            "probe JVM should finish within 30 s; output:%n%s%nthreads:%n%s",
            run.output(), run.threads())
        .isTrue();
    assertThat(run.exitValue()).as("probe JVM exit value: " + run.output()).isZero();
    return run.result();
  }

  /**
   * The probe's operation was delayed: the plan's scenario applied (a hook that matches but does
   * not apply counts 0), and the measured call took the whole delay.
   */
  private static void assertDelayed(final ChaosPlan plan, final ChildJvmProbe.ProbeResult result) {
    final String id = plan.scenarios().get(0).id();
    assertThat(result.longValue("applied." + id))
        .as("applications of %s, output:%n%s", id, result.output())
        .isPositive();
    assertThat(result.longValue("elapsedMillis"))
        .as("the delayed call of %s, output:%n%s", id, result.output())
        .isGreaterThanOrEqualTo(DELAY_MS);
  }
}
