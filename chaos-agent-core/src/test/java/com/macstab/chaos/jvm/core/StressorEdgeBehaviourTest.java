package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Edge behaviour of the stressors: the filters, caps and clamps that shape what they do, the
 * diagnostics they log, and a {@code close()} that is itself interrupted. Sizes are minimal and
 * every stressor is closed before the test ends.
 */
@DisplayName("Stressor edge behaviour")
class StressorEdgeBehaviourTest {

  private static final Duration HOUR = Duration.ofHours(1);

  /**
   * Closes {@code stressor} from a thread whose interrupt flag is set, and reports whether the flag
   * is still set afterwards (clearing it): close() must finish its work and hand the interrupt
   * back.
   */
  private static boolean closeWhileInterrupted(final ManagedStressor stressor) {
    Thread.currentThread().interrupt();
    stressor.close();
    return Thread.interrupted();
  }

  /** An {@link Instrumentation} that serves a fixed class list and records retransforms. */
  private static final class FakeInstrumentation {
    private final List<Class<?>> loaded;
    private final boolean retransformSupported;
    private final Set<Class<?>> unmodifiable;
    private final Set<Class<?>> failing;
    private final Consumer<Class<?>> onRetransform;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<Class<?>> attempted = new CopyOnWriteArrayList<>();
    final List<Class<?>> retransformed = new CopyOnWriteArrayList<>();

    FakeInstrumentation(
        final List<Class<?>> loaded,
        final boolean retransformSupported,
        final Set<Class<?>> unmodifiable,
        final Set<Class<?>> failing,
        final Consumer<Class<?>> onRetransform) {
      this.loaded = loaded;
      this.retransformSupported = retransformSupported;
      this.unmodifiable = unmodifiable;
      this.failing = failing;
      this.onRetransform = onRetransform;
    }

    FakeInstrumentation(final List<Class<?>> loaded) {
      this(loaded, true, Set.of(), Set.of(), cls -> {});
    }

    Optional<Instrumentation> asOptional() {
      return Optional.of(
          (Instrumentation)
              Proxy.newProxyInstance(
                  Instrumentation.class.getClassLoader(),
                  new Class<?>[] {Instrumentation.class},
                  (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                      case "isRetransformClassesSupported" -> retransformSupported;
                      case "getAllLoadedClasses" -> loaded.toArray(new Class<?>[0]);
                      case "isModifiableClass" -> !unmodifiable.contains((Class<?>) args[0]);
                      case "retransformClasses" -> retransform((Class<?>[]) args[0]);
                      default -> throw new UnsupportedOperationException(method.getName());
                    };
                  }));
    }

    private @Nullable Object retransform(final Class<?>[] classes)
        throws UnmodifiableClassException {
      for (final Class<?> cls : classes) {
        attempted.add(cls);
        onRetransform.accept(cls);
        if (failing.contains(cls)) {
          throw new UnmodifiableClassException(cls.getName());
        }
        retransformed.add(cls);
      }
      return null;
    }
  }

  @Nested
  @DisplayName("SafepointStormStressor")
  class SafepointStorm {

    @Test
    @DisplayName("retransforms only ordinary modifiable classes and survives a failed retransform")
    void retransformsOnlySafeClasses() throws Exception {
      final Runnable lambda = () -> {};
      final Class<?> hidden = lambda.getClass();
      assertThat(hidden.isHidden()).isTrue();
      final FakeInstrumentation instrumentation =
          new FakeInstrumentation(
              List.of(
                  int[].class,
                  int.class,
                  hidden,
                  SafepointStormStressor.class,
                  net.bytebuddy.ByteBuddy.class,
                  Class.forName("jdk.internal.misc.Unsafe"),
                  Thread.class,
                  Integer.class,
                  String.class,
                  Long.class),
              true,
              Set.of(Thread.class),
              Set.of(Integer.class),
              cls -> {});
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(HOUR, 100), instrumentation.asOptional());
      try {
        await().atMost(5, TimeUnit.SECONDS).until(() -> instrumentation.attempted.size() >= 3);
      } finally {
        stressor.close();
      }
      assertThat(instrumentation.attempted)
          .containsExactly(Integer.class, String.class, Long.class);
      assertThat(instrumentation.retransformed).containsExactly(String.class, Long.class);
    }

    @Test
    @DisplayName("stops retransforming once the requested number of classes is reached")
    void stopsAtRequestedCount() {
      final FakeInstrumentation instrumentation =
          new FakeInstrumentation(List.of(String.class, Long.class, Integer.class));
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(HOUR, 2), instrumentation.asOptional());
      try {
        await().atMost(5, TimeUnit.SECONDS).until(() -> instrumentation.retransformed.size() >= 2);
      } finally {
        stressor.close();
      }
      assertThat(instrumentation.retransformed).containsExactly(String.class, Long.class);
    }

    @Test
    @DisplayName("does not enumerate classes when the JVM cannot retransform")
    void skipsWhenRetransformUnsupported() {
      final FakeInstrumentation instrumentation =
          new FakeInstrumentation(List.of(String.class), false, Set.of(), Set.of(), cls -> {});
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(HOUR, 5), instrumentation.asOptional());
      try {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> instrumentation.calls.contains("isRetransformClassesSupported"));
      } finally {
        stressor.close();
      }
      assertThat(instrumentation.calls).doesNotContain("getAllLoadedClasses");
      assertThat(instrumentation.retransformed).isEmpty();
    }

    @Test
    @DisplayName("still cycles GC when retransforms are requested without instrumentation")
    void cyclesWithoutInstrumentation() {
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(HOUR, 5), Optional.empty());
      try {
        await().atMost(5, TimeUnit.SECONDS).until(() -> stressor.gcCycleCount() > 0);
      } finally {
        stressor.close();
      }
      assertThat(stressor.gcCycleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a close() during a cycle ends the storm without starting another")
    void closeDuringCycleEndsStorm() {
      final AtomicBoolean entered = new AtomicBoolean();
      final FakeInstrumentation instrumentation =
          new FakeInstrumentation(
              List.of(String.class),
              true,
              Set.of(),
              Set.of(),
              cls -> {
                entered.set(true);
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                  Thread.onSpinWait();
                }
              });
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(Duration.ofMillis(1), 1),
              instrumentation.asOptional());
      await().atMost(5, TimeUnit.SECONDS).untilTrue(entered);

      stressor.close();

      assertThat(stressor.gcCycleCount()).isEqualTo(1);
      assertThat(instrumentation.attempted).containsExactly(String.class);
    }

    @Test
    @DisplayName("an interrupted close() hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final AtomicBoolean entered = new AtomicBoolean();
      final AtomicBoolean released = new AtomicBoolean();
      final FakeInstrumentation instrumentation =
          new FakeInstrumentation(
              List.of(String.class),
              true,
              Set.of(),
              Set.of(),
              cls -> {
                entered.set(true);
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!released.get() && System.nanoTime() < deadline) {
                  LockSupport.parkNanos(1_000_000L);
                }
              });
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(HOUR, 1), instrumentation.asOptional());
      await().atMost(5, TimeUnit.SECONDS).untilTrue(entered);
      try {
        assertThat(closeWhileInterrupted(stressor)).isTrue();
      } finally {
        released.set(true);
        stressor.close();
      }
      assertThat(stressor.gcCycleCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("GcPressureStressor")
  class GcPressure {

    private Optional<Thread> allocationThread() {
      return Thread.getAllStackTraces().keySet().stream()
          .filter(thread -> thread.getName().equals("chaos-gc-pressure") && thread.isAlive())
          .findFirst();
    }

    @Test
    @DisplayName("stops by itself when its duration ends, promoting chunks meanwhile")
    void endsAfterDuration() {
      try (LogCapture log = LogCapture.agent()) {
        final GcPressureStressor stressor =
            new GcPressureStressor(
                new ChaosEffect.GcPressureEffect(100_000L, 1024, true, Duration.ofMillis(150)));
        try {
          await().atMost(5, TimeUnit.SECONDS).until(() -> !stressor.isRunning());
          await()
              .atMost(5, TimeUnit.SECONDS)
              .until(() -> log.messages().contains("chaos-gc-pressure stressor completed"));
        } finally {
          stressor.close();
        }
        assertThat(stressor.isRunning()).isFalse();
      }
    }

    @Test
    @DisplayName("close() stops a batch in flight instead of finishing it")
    void closeStopsBatchInFlight() throws Exception {
      // 2e9 one-byte objects per batch: a batch runs for seconds, so close() lands inside it.
      final GcPressureStressor stressor =
          new GcPressureStressor(new ChaosEffect.GcPressureEffect(40_000_000_000L, 1, false, HOUR));
      try {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(
                () ->
                    allocationThread()
                        .map(thread -> thread.getState() == Thread.State.RUNNABLE)
                        .orElse(false));
        Thread.sleep(50);
        final long started = System.nanoTime();
        stressor.close();
        assertThat(stressor.isRunning()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("an interrupted close() hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final GcPressureStressor stressor =
          new GcPressureStressor(new ChaosEffect.GcPressureEffect(1_000L, 1024, false, HOUR));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      assertThat(stressor.isRunning()).isFalse();
    }
  }

  @Nested
  @DisplayName("VirtualThreadCarrierPinningStressor")
  class CarrierPinning {

    private static final String PARALLELISM = "jdk.virtualThreadScheduler.parallelism";
    private String previous;

    @BeforeEach
    void rememberParallelism() {
      previous = System.getProperty(PARALLELISM);
    }

    @AfterEach
    void restoreParallelism() {
      if (previous == null) {
        System.clearProperty(PARALLELISM);
      } else {
        System.setProperty(PARALLELISM, previous);
      }
    }

    @Test
    @DisplayName("the monitor-pinning mode holds a carrier and releases it on close()")
    void monitorModePinsAndReleases() {
      try (LogCapture log = LogCapture.agent()) {
        final VirtualThreadCarrierPinningStressor stressor =
            new VirtualThreadCarrierPinningStressor(
                new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(20)), true);
        assertThat(stressor.aliveCount()).isEqualTo(1);
        // Let the virtual thread mount and enter its pinning loop before stopping it.
        await().pollDelay(Duration.ofMillis(100)).atMost(5, TimeUnit.SECONDS).until(() -> true);
        assertThat(stressor.aliveCount()).isEqualTo(1);
        stressor.close();
        assertThat(stressor.aliveCount()).isZero();
        assertThat(log.messages())
            .contains("chaos carrier-pin thread terminated: chaos-carrier-pin-0");
      }
    }

    @Test
    @DisplayName("an effect that leaves a carrier free is kept as it is")
    void keepsEffectThatLeavesACarrier() {
      System.setProperty(PARALLELISM, "4");
      final ChaosEffect.VirtualThreadCarrierPinningEffect effect =
          new ChaosEffect.VirtualThreadCarrierPinningEffect(2, Duration.ofMillis(10));
      assertThat(VirtualThreadCarrierPinningStressor.leavingOneCarrier(effect)).isSameAs(effect);
    }

    @Test
    @DisplayName("a single-carrier scheduler skips pinning and says why")
    void singleCarrierSkips() {
      System.setProperty(PARALLELISM, " 1 ");
      try (LogCapture log = LogCapture.agent()) {
        assertThat(
                VirtualThreadCarrierPinningStressor.leavingOneCarrier(
                    new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(10))))
            .isNull();
        assertThat(log.messages())
            .anyMatch(message -> message.contains("the scheduler has a single carrier"));
      }
    }

    @Test
    @DisplayName("a single-carrier scheduler makes the factory create no stressor")
    void factoryCreatesNothingOnSingleCarrier() {
      System.setProperty(PARALLELISM, "1");
      final StressorFactory factory = new StressorFactory(Optional::empty);
      assertThat(
              factory.createIfNeeded(
                  new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(10)),
                  false,
                  ManagedStressor.NO_END))
          .isNull();
    }

    @Test
    @DisplayName("an unparsable parallelism setting falls back to the processor count")
    void unparsableParallelismFallsBack() {
      System.setProperty(PARALLELISM, "many");
      assertThat(VirtualThreadCarrierPinningStressor.schedulerParallelism())
          .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    @DisplayName(
        "an interrupted close() still stops the pinning threads and hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(20)), true);
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      assertThat(stressor.aliveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("DeadlockStressor")
  class Deadlock {

    @Test
    @DisplayName("without an acquisition delay the participants still deadlock")
    void zeroDelayDeadlocks() {
      final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ZERO));
      try (LogCapture log = LogCapture.agent()) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(
                () -> {
                  final long[] ids = threads.findDeadlockedThreads();
                  return ids != null
                      && Arrays.stream(threads.getThreadInfo(ids))
                          .map(ThreadInfo::getThreadName)
                          .toList()
                          .containsAll(List.of("chaos-deadlock-0", "chaos-deadlock-1"));
                });
        stressor.close();
        assertThat(stressor.aliveCount()).isZero();
        // close() interrupts the participants one after another: the first leaves its lock wait
        // and logs; the next may take the lock it released before its own interrupt arrives and
        // then leaves through the park loop without logging. At least one is interrupted.
        assertThat(log.messages())
            .anyMatch(message -> message.startsWith("chaos deadlock thread interrupted: "));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("an interrupted close() still stops the participants and hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final DeadlockStressor stressor =
          new DeadlockStressor(new ChaosEffect.DeadlockEffect(2, Duration.ZERO));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(stressor.aliveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("MonitorContentionStressor")
  class MonitorContention {

    @Test
    @DisplayName("an interrupted close() still stops the contenders and hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(5), 2, false));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      assertThat(stressor.aliveCount()).isZero();
    }
  }

  @Nested
  @DisplayName("CodeCachePressureStressor")
  class CodeCachePressure {

    @Test
    @DisplayName("close() stops the compiler thread between classes")
    void closeStopsCompilerMidway() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(3, 200));
      final Thread compiler = stressor.compilerThread();
      assertThat(compiler).isNotNull();
      stressor.close();
      assertThat(compiler.isAlive()).isFalse();
      assertThat(stressor.compilerThread()).isNull();
      assertThat(stressor.retainedClassCount()).isZero();
    }

    @Test
    @DisplayName("a second close() finds nothing left to release")
    void secondCloseIsNoOp() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(1, 1));
      stressor.close();
      assertThatCode(stressor::close).doesNotThrowAnyException();
      assertThat(stressor.compilerThread()).isNull();
      assertThat(stressor.retainedClassCount()).isZero();
    }

    @Test
    @DisplayName("an interrupted close() releases the classes and hands the interrupt back")
    void interruptedCloseRestoresInterrupt() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(2, 200));
      final Thread compiler = stressor.compilerThread();
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(stressor.retainedClassCount()).isZero();
      await().atMost(5, TimeUnit.SECONDS).until(() -> !compiler.isAlive());
    }
  }

  @Nested
  @DisplayName("ThreadLocalLeakStressor")
  class ThreadLocalLeak {

    @Test
    @DisplayName("a total entry count beyond int is rejected before anything is planted")
    void rejectsOverflowingTotal() {
      final int parallelism = ForkJoinPool.commonPool().getParallelism();
      assumeThat(parallelism).isGreaterThan(1);
      assertThatThrownBy(
              () ->
                  new ThreadLocalLeakStressor(
                      new ChaosEffect.ThreadLocalLeakEffect(Integer.MAX_VALUE, 1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "thread-local-leak total entries (parallelism * entriesPerThread) overflows int: "
                  + parallelism
                  + " * "
                  + Integer.MAX_VALUE)
          .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("each planted entry is logged with the worker that holds it")
    void logsEachPlantedEntry() {
      try (LogCapture log = LogCapture.agent()) {
        final ThreadLocalLeakStressor stressor =
            new ThreadLocalLeakStressor(new ChaosEffect.ThreadLocalLeakEffect(1, 1));
        stressor.close();
        assertThat(log.messages())
            .filteredOn(message -> message.startsWith("chaos thread-local-leak planted entry on "))
            .hasSize(stressor.plantedCount());
      }
    }
  }

  @Nested
  @DisplayName("a duration too large for nanoseconds runs until the stressor is stopped")
  class HugeDurations {

    private final Duration forever = Duration.ofSeconds(Long.MAX_VALUE);

    @Test
    @DisplayName("GC pressure, a thread leak, carrier pinning and monitor contention keep running")
    void hugeDurationsKeepRunning() {
      final GcPressureStressor gc =
          new GcPressureStressor(new ChaosEffect.GcPressureEffect(1_000L, 64, false, forever));
      final ThreadLeakStressor leak =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(1, "huge-leak-", true, forever));
      final VirtualThreadCarrierPinningStressor pinning =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, forever));
      final MonitorContentionStressor contention =
          new MonitorContentionStressor(new ChaosEffect.MonitorContentionEffect(forever, 2, false));
      try {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));

        assertThat(gc.isRunning()).as("GC pressure").isTrue();
        assertThat(leak.aliveCount()).as("leaked threads").isEqualTo(1);
        assertThat(pinning.aliveCount()).as("pinning threads").isEqualTo(1);
        assertThat(contention.aliveCount()).as("contending threads").isEqualTo(2);
      } finally {
        gc.close();
        leak.close();
        pinning.close();
        contention.close();
      }
    }
  }

  @Nested
  @DisplayName("StringInternPressureStressor")
  class StringInternPressure {

    @Test
    @DisplayName("1,000 strings of two characters are 1,000 distinct strings of that length")
    void shortStringsAreDistinct() {
      final StringInternPressureStressor stressor =
          new StringInternPressureStressor(new ChaosEffect.StringInternPressureEffect(1_000, 2));
      try {
        assertThat(stressor.internedCount()).isEqualTo(1_000);
        assertThat(stressor.distinctCount()).isEqualTo(1_000);
      } finally {
        stressor.close();
      }
    }
  }

  @Nested
  @DisplayName("thread-owning stressors")
  class ThreadOwning {

    @Test
    @DisplayName("ThreadLeakStressor logs each thread it ends")
    void threadLeakLogsTermination() {
      try (LogCapture log = LogCapture.agent()) {
        final ThreadLeakStressor stressor =
            new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(1, "edge-leak-", true, null));
        stressor.close();
        assertThat(stressor.aliveCount()).isZero();
        assertThat(log.messages()).contains("chaos thread-leak thread terminated: edge-leak-0");
      }
    }

    @Test
    @DisplayName("ThreadLeakStressor: an interrupted close() hands the interrupt back")
    void threadLeakInterruptedClose() {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(1, "edge-leak-", true, null));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      assertThat(stressor.aliveCount()).isZero();
    }

    @Test
    @DisplayName("KeepAliveStressor: an interrupted close() hands the interrupt back")
    void keepAliveInterruptedClose() {
      final KeepAliveStressor stressor =
          new KeepAliveStressor(new ChaosEffect.KeepAliveEffect("edge-keepalive", true, HOUR));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      assertThat(
              Thread.getAllStackTraces().keySet().stream()
                  .anyMatch(thread -> thread.getName().equals("edge-keepalive")))
          .isFalse();
    }

    @Test
    @DisplayName("ReferenceQueueFloodStressor: an interrupted close() hands the interrupt back")
    void referenceQueueInterruptedClose() {
      final ReferenceQueueFloodStressor stressor =
          new ReferenceQueueFloodStressor(new ChaosEffect.ReferenceQueueFloodEffect(1, HOUR));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      stressor.close();
      final long cycles = stressor.floodCycleCount();
      assertThat(cycles).isLessThanOrEqualTo(1);
    }
  }

  @Nested
  @DisplayName("a close() under interrupt")
  class CloseUnderInterrupt {

    @Test
    @DisplayName("ThreadLeakStressor: every thread has ended when close() returns")
    void threadLeak() {
      final ThreadLeakStressor stressor =
          new ThreadLeakStressor(new ChaosEffect.ThreadLeakEffect(64, "edge-leak-", true, null));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(stressor.aliveCount()).isZero();
    }

    @Test
    @DisplayName("MonitorContentionStressor: every contender has ended when close() returns")
    void monitorContention() {
      final MonitorContentionStressor stressor =
          new MonitorContentionStressor(
              new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(5), 16, false));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(stressor.aliveCount()).isZero();
    }

    @Test
    @DisplayName("KeepAliveStressor: the thread has ended when close() returns")
    void keepAlive() {
      final KeepAliveStressor stressor =
          new KeepAliveStressor(new ChaosEffect.KeepAliveEffect("edge-keepalive-2", true, HOUR));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(threadNamed("edge-keepalive-2")).isFalse();
    }

    @Test
    @DisplayName("GcPressureStressor: the allocation thread has ended when close() returns")
    void gcPressure() {
      final GcPressureStressor stressor =
          new GcPressureStressor(new ChaosEffect.GcPressureEffect(1024L, 1024, false, HOUR));
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(threadNamed("chaos-gc-pressure")).isFalse();
    }

    @Test
    @DisplayName("VirtualThreadCarrierPinningStressor: every pinning thread has ended")
    void carrierPinning() {
      final VirtualThreadCarrierPinningStressor stressor =
          new VirtualThreadCarrierPinningStressor(
              new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMillis(5)), true);
      assertThat(closeWhileInterrupted(stressor)).isTrue();
      assertThat(stressor.aliveCount()).isZero();
    }

    private static boolean threadNamed(final String name) {
      return Thread.getAllStackTraces().keySet().stream()
          .anyMatch(thread -> thread.getName().equals(name) && thread.isAlive());
    }
  }

  @Nested
  @DisplayName("a partial start")
  class PartialStart {

    @Test
    @DisplayName("a thread that cannot start stops the threads already started")
    void failedThirdStartLeavesNoThread() {
      final AtomicInteger made = new AtomicInteger();
      final java.util.concurrent.ThreadFactory failingThird =
          body ->
              made.incrementAndGet() == 3
                  ? new Thread(body) {
                    @Override
                    public void start() {
                      throw new OutOfMemoryError("unable to create native thread");
                    }
                  }
                  : new Thread(body);

      assertThatThrownBy(
              () ->
                  new ThreadLeakStressor(
                      new ChaosEffect.ThreadLeakEffect(5, "edge-partial-", true, null),
                      ManagedStressor.NO_END,
                      failingThird))
          .isInstanceOf(OutOfMemoryError.class);

      assertThat(
              Thread.getAllStackTraces().keySet().stream()
                  .anyMatch(thread -> thread.getName().startsWith("edge-partial-")))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("FinalizerBacklogStressor")
  class FinalizerBacklog {

    @Test
    @DisplayName("reports how many finalizable objects it created and their delay")
    void logsCreation() {
      try (LogCapture log = LogCapture.agent()) {
        final FinalizerBacklogStressor stressor =
            new FinalizerBacklogStressor(
                new ChaosEffect.FinalizerBacklogEffect(1, Duration.ofMillis(3)));
        stressor.close();
        assertThat(log.messages())
            .contains(
                "chaos finalizer-backlog stressor created 1 objects with 3 ms finalizer delay");
      }
    }
  }

  @Nested
  @DisplayName("AgentThreads end callback")
  class EndCallback {

    @Test
    @DisplayName(
        "a failing body is logged once and reported; the thread ends without an uncaught throw")
    void failingBodyIsReported() throws InterruptedException {
      final AtomicInteger reports = new AtomicInteger();
      final AtomicReference<@Nullable Throwable> uncaught = new AtomicReference<>();
      try (LogCapture log = LogCapture.agent()) {
        final Thread thread =
            AgentThreads.platform(
                "edge-failing-body",
                true,
                () -> {
                  throw new IllegalStateException("body failed");
                },
                reports::incrementAndGet);
        thread.setUncaughtExceptionHandler((failed, throwable) -> uncaught.set(throwable));
        AgentThreads.startAll(List.of(thread));
        thread.join(5_000L);

        assertThat(reports).hasValue(1);
        assertThat(uncaught.get()).isNull();
        assertThat(log.records())
            .filteredOn(record -> record.getLevel() == java.util.logging.Level.WARNING)
            .singleElement()
            .satisfies(record -> assertThat(record.getThrown()).hasMessage("body failed"));
      }
    }

    @Test
    @DisplayName("a report that fails is logged once and swallowed")
    void failingReportIsSwallowed() throws InterruptedException {
      final AtomicReference<@Nullable Throwable> uncaught = new AtomicReference<>();
      try (LogCapture log = LogCapture.agent()) {
        final Thread thread =
            AgentThreads.platform(
                "edge-failing-report",
                true,
                () -> {
                  throw new IllegalStateException("body failed");
                },
                () -> {
                  throw new OutOfMemoryError("report failed");
                });
        thread.setUncaughtExceptionHandler((failed, throwable) -> uncaught.set(throwable));
        AgentThreads.startAll(List.of(thread));
        thread.join(5_000L);

        assertThat(uncaught.get()).isNull();
        assertThat(log.records())
            .filteredOn(record -> record.getLevel() == java.util.logging.Level.WARNING)
            .extracting(record -> record.getThrown().getMessage())
            .containsExactly("body failed", "report failed");
      }
    }

    @Test
    @DisplayName("a body that returns reports nothing")
    void returningBodyReportsNothing() throws InterruptedException {
      final AtomicInteger reports = new AtomicInteger();
      final Thread thread =
          AgentThreads.start("edge-returning-body", true, () -> {}, reports::incrementAndGet);
      thread.join(5_000L);

      assertThat(reports).hasValue(0);
    }
  }
}
