package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.CountDownLatch;
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
              .until(
                  () ->
                      log.messages()
                          .contains("[chaos-agent] chaos-gc-pressure stressor completed"));
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
            .contains("[chaos-agent] chaos carrier-pin thread terminated: chaos-carrier-pin-0");
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
            .anyMatch(
                message -> message.startsWith("[chaos-agent] chaos deadlock thread interrupted: "));
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
      assertThatThrownBy(
              () ->
                  new ThreadLocalLeakStressor(
                      new ChaosEffect.ThreadLocalLeakEffect(Integer.MAX_VALUE, 1), 2))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "thread-local-leak total entries (parallelism * entriesPerThread) overflows int: 2 * "
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
            .filteredOn(
                message ->
                    message.startsWith("[chaos-agent] chaos thread-local-leak planted entry on "))
            .hasSize(stressor.plantedCount());
      }
    }

    @Test
    @DisplayName("the plant timeout starts once every value is allocated")
    void plantTimeoutStartsAfterAllocation() {
      final long timeout = TimeUnit.MILLISECONDS.toNanos(200);
      final ThreadLocalLeakStressor stressor =
          new ThreadLocalLeakStressor(
              new ChaosEffect.ThreadLocalLeakEffect(1, 1),
              1,
              timeout,
              valueSizeBytes -> {
                // Stands for zeroing gigabytes of values: longer than the whole plant timeout.
                final long end = System.nanoTime() + 2 * timeout;
                while (end - System.nanoTime() > 0) {
                  LockSupport.parkNanos(end - System.nanoTime());
                }
                return new byte[valueSizeBytes];
              });
      try {
        assertThat(stressor.plantedCount()).isEqualTo(1);
        assertThat(stressor.retainedBytes()).isEqualTo(1L);
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("on a busy common pool the entries are planted by its workers, not the caller")
    void plantsOnPoolWorkersOnly() throws Exception {
      final ForkJoinPool pool = ForkJoinPool.commonPool();
      final CountDownLatch release = new CountDownLatch(1);
      final CountDownLatch busy = new CountDownLatch(pool.getParallelism());
      final AtomicReference<@Nullable ThreadLocalLeakStressor> built = new AtomicReference<>();
      final Thread activating =
          new Thread(
              () ->
                  built.set(
                      new ThreadLocalLeakStressor(new ChaosEffect.ThreadLocalLeakEffect(1, 1))),
              "activating-application-thread");
      try (LogCapture log = LogCapture.agent()) {
        try {
          for (int i = 0; i < pool.getParallelism(); i++) {
            pool.execute(
                () -> {
                  busy.countDown();
                  try {
                    release.await();
                  } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });
          }
          assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
          // Every worker is busy: the planting tasks wait in the activating thread's queue.
          activating.start();
          activating.join(300);
        } finally {
          release.countDown();
        }
        activating.join(TimeUnit.SECONDS.toMillis(ThreadLocalLeakStressor.PLANT_TIMEOUT_SECONDS));

        final ThreadLocalLeakStressor stressor = built.get();
        assertThat(stressor).as("the stressor was built").isNotNull();
        stressor.close();
        assertThat(log.messages())
            .filteredOn(
                message ->
                    message.startsWith("[chaos-agent] chaos thread-local-leak planted entry on "))
            .hasSize(stressor.plantedCount())
            .noneMatch(message -> message.endsWith("activating-application-thread"))
            .allMatch(message -> message.contains("ForkJoinPool.commonPool-worker-"));
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
        assertThat(log.messages())
            .contains("[chaos-agent] chaos thread-leak thread terminated: edge-leak-0");
      }
    }

    @Test
    @DisplayName("ThreadLeakStressor logs a thread it ends while the thread is parked")
    void threadLeakLogsTerminationOfParkedThread() {
      try (LogCapture log = LogCapture.agent()) {
        final ThreadLeakStressor stressor =
            new ThreadLeakStressor(
                new ChaosEffect.ThreadLeakEffect(1, "edge-leak-parked-", true, null));
        // close() interrupts the thread inside its park, not before its first loop check.
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(
                            thread ->
                                thread.getName().equals("edge-leak-parked-0")
                                    && thread.getState() == Thread.State.TIMED_WAITING));
        stressor.close();
        assertThat(stressor.aliveCount()).isZero();
        assertThat(log.messages())
            .contains("[chaos-agent] chaos thread-leak thread terminated: edge-leak-parked-0");
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
                "[chaos-agent] chaos finalizer-backlog stressor created 1 objects with PT0.003S"
                    + " finalizer delay");
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

  @Nested
  @DisplayName("AgentThreads context class loader")
  class ContextClassLoader {

    @Test
    @DisplayName("an agent thread keeps no loader of the thread that made it")
    void agentThreadHasNoContextClassLoader() {
      // Arrange
      final Thread self = Thread.currentThread();
      final ClassLoader original = self.getContextClassLoader();
      final ClassLoader application = new java.net.URLClassLoader(new java.net.URL[0], null);
      final Thread expiryLike;
      final Thread stressorLike;
      self.setContextClassLoader(application);
      try {
        // Act
        expiryLike = AgentThreads.platform("edge-tccl-expiry", true, () -> {});
        stressorLike = AgentThreads.platform("edge-tccl-stressor", true, () -> {}, () -> {});
      } finally {
        self.setContextClassLoader(original);
      }

      // Assert
      assertThat(expiryLike.getContextClassLoader()).isNull();
      assertThat(stressorLike.getContextClassLoader()).isNull();
    }

    @Test
    @DisplayName(
        "a running agent thread keeps no loader through the inheritable thread-locals of the thread"
            + " that made it")
    void agentThreadInheritsNoThreadLocals() throws Exception {
      // Arrange
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      REQUEST_CONTEXT.set(LoaderFrame.in(application));
      application = null;
      final java.util.concurrent.CountDownLatch release =
          new java.util.concurrent.CountDownLatch(1);
      final List<Thread> threads;
      try {
        // Act
        threads =
            AgentThreads.startAll(
                List.of(
                    AgentThreads.platform("edge-itl-expiry", true, () -> awaitQuietly(release)),
                    AgentThreads.platform(
                        "edge-itl-stressor", true, () -> awaitQuietly(release), () -> {})));
      } finally {
        REQUEST_CONTEXT.remove();
      }
      try {
        // Assert
        assertThat(collected(loader)).as("the loader is collected while the threads run").isTrue();
      } finally {
        release.countDown();
        AgentThreads.stopAll(threads, 5_000L);
      }
    }

    @Test
    @DisplayName(
        "a running agent thread keeps no loader through the access-control context of the frames"
            + " that made it (JDK 21)")
    void agentThreadKeepsNoCallerFrames() throws Exception {
      // Arrange
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      final java.util.concurrent.CountDownLatch release =
          new java.util.concurrent.CountDownLatch(1);
      final AtomicReference<List<Thread>> threads = new AtomicReference<>(List.of());
      // Act: the threads are made from a frame of a class the throwaway loader defined
      LoaderFrame.in(application)
          .accept(
              () ->
                  threads.set(
                      AgentThreads.startAll(
                          List.of(
                              AgentThreads.platform(
                                  "edge-acc-expiry", true, () -> awaitQuietly(release)),
                              AgentThreads.platform(
                                  "edge-acc-stressor",
                                  true,
                                  () -> awaitQuietly(release),
                                  () -> {})))));
      application = null;
      try {
        // Assert
        assertThat(collected(loader)).as("the loader is collected while the threads run").isTrue();
      } finally {
        release.countDown();
        AgentThreads.stopAll(threads.get(), 5_000L);
      }
    }

    @Test
    @DisplayName(
        "the event delivery thread of an asynchronous listener keeps no loader through the context"
            + " class loader or the inheritable thread-locals of the thread that registered it")
    @SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
    void eventDeliveryThreadKeepsNoRegisteringThreadsLoader() throws Exception {
      // Arrange
      final ObservabilityBus bus =
          new ObservabilityBus(com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP);
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      final Thread self = Thread.currentThread();
      final ClassLoader original = self.getContextClassLoader();
      self.setContextClassLoader(application);
      REQUEST_CONTEXT.set(LoaderFrame.in(application));
      application = null;
      try {
        // Act
        bus.addListener(com.macstab.chaos.jvm.api.ChaosEventListener.async(event -> {}));
      } finally {
        REQUEST_CONTEXT.remove();
        self.setContextClassLoader(original);
      }
      try {
        // Assert
        assertThat(collected(loader))
            .as("the loader is collected while the delivery thread runs")
            .isTrue();
      } finally {
        bus.close();
      }
    }

    @Test
    @DisplayName(
        "a running thread-leak stressor keeps no loader through the context class loader or the"
            + " inheritable thread-locals of the thread that activated it")
    void threadLeakKeepsNoActivatingThreadsLoader() throws Exception {
      // Arrange
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      final Thread self = Thread.currentThread();
      final ClassLoader original = self.getContextClassLoader();
      self.setContextClassLoader(application);
      REQUEST_CONTEXT.set(LoaderFrame.in(application));
      application = null;
      final ThreadLeakStressor stressor;
      try {
        // Act
        stressor =
            new ThreadLeakStressor(
                new ChaosEffect.ThreadLeakEffect(1, "edge-itl-leak-", true, null), reason -> {});
      } finally {
        REQUEST_CONTEXT.remove();
        self.setContextClassLoader(original);
      }
      try {
        // Assert
        assertThat(collected(loader)).as("the loader is collected while the leak runs").isTrue();
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName(
        "a running carrier-pinning stressor keeps no loader through the context class loader or"
            + " the inheritable thread-locals of the thread that activated it")
    void carrierPinningKeepsNoActivatingThreadsLoader() throws Exception {
      // Arrange
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      final Thread self = Thread.currentThread();
      final ClassLoader original = self.getContextClassLoader();
      self.setContextClassLoader(application);
      REQUEST_CONTEXT.set(LoaderFrame.in(application));
      application = null;
      final VirtualThreadCarrierPinningStressor stressor;
      try {
        // Act: one virtual thread that holds its carrier until the stressor is closed
        stressor =
            new VirtualThreadCarrierPinningStressor(
                new ChaosEffect.VirtualThreadCarrierPinningEffect(1, Duration.ofMinutes(1)), false);
      } finally {
        REQUEST_CONTEXT.remove();
        self.setContextClassLoader(original);
      }
      try {
        // Assert
        assertThat(collected(loader)).as("the loader is collected while the pinning runs").isTrue();
      } finally {
        stressor.close();
      }
    }

    /**
     * Fails only on JDK 21 to 23, where a thread keeps the access-control context of the frames
     * that made it; JDK 24 dropped that context, so there it passes either way. {@link
     * #callerFramesKeepTheLoaderOfAPlainThread} proves that the frame of the throwaway loader's
     * class does carry that loader into the context of a thread made outside {@code doPrivileged}.
     */
    @Test
    @DisplayName(
        "the event delivery thread of an asynchronous listener keeps no loader through the"
            + " access-control context of the frames that registered it (JDK 21)")
    @SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
    void eventDeliveryThreadKeepsNoCallerFrames() throws Exception {
      // Arrange
      final ObservabilityBus bus =
          new ObservabilityBus(com.macstab.chaos.jvm.api.ChaosMetricsSink.NOOP);
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      // Act: the listener is registered from a frame of a class the throwaway loader defined
      LoaderFrame.in(application)
          .accept(
              () -> bus.addListener(com.macstab.chaos.jvm.api.ChaosEventListener.async(e -> {})));
      application = null;
      try {
        // Assert
        assertThat(collected(loader))
            .as("the loader is collected while the delivery thread runs")
            .isTrue();
      } finally {
        bus.close();
      }
    }

    /**
     * The control of the caller-frame tests: the copy of {@link LoaderFrame} that the throwaway
     * loader defines has a protection domain that references that loader, so a thread made with
     * {@code new Thread} from its frame keeps the loader through the access-control context it
     * inherits, and through nothing else: the loader is collected once the thread has ended.
     */
    @Test
    @DisplayName(
        "a thread made with new Thread from a frame of the throwaway loader's class keeps that"
            + " loader until it ends (JDK 21 to 23), so the caller-frame tests can fail")
    void callerFramesKeepTheLoaderOfAPlainThread() throws Exception {
      org.junit.jupiter.api.Assumptions.assumeTrue(
          Runtime.version().feature() < 24,
          "JDK 24 dropped the access-control context a thread inherits");
      // Arrange
      ClassLoader application = new ThrowawayLoader();
      final java.lang.ref.WeakReference<ClassLoader> loader =
          new java.lang.ref.WeakReference<>(application);
      final java.util.concurrent.CountDownLatch release =
          new java.util.concurrent.CountDownLatch(1);
      final AtomicReference<@Nullable Thread> plain = new AtomicReference<>();
      // Act: an ordinary thread, made from a frame of a class the throwaway loader defined
      LoaderFrame.in(application)
          .accept(
              () -> {
                final Thread thread = new Thread(() -> awaitQuietly(release), "edge-acc-plain");
                thread.setDaemon(true);
                thread.start();
                plain.set(thread);
              });
      application = null;
      final boolean keptWhileRunning;
      try {
        keptWhileRunning = survives(loader);
      } finally {
        release.countDown();
        final Thread thread = plain.getAndSet(null);
        if (thread != null) {
          thread.join(5_000L);
        }
      }

      // Assert
      assertThat(keptWhileRunning).as("the running thread keeps the loader").isTrue();
      assertThat(collected(loader)).as("the loader is collected once the thread ended").isTrue();
    }

    /** Whether {@code loader} survives five full GCs. */
    private static boolean survives(final java.lang.ref.WeakReference<ClassLoader> loader)
        throws InterruptedException {
      for (int gc = 0; gc < 5 && loader.get() != null; gc++) {
        System.gc();
        Thread.sleep(20);
      }
      return loader.get() != null;
    }

    private static void awaitQuietly(final java.util.concurrent.CountDownLatch release) {
      try {
        release.await();
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    /** Whether a full GC clears {@code loader} within 10 s. */
    private static boolean collected(final java.lang.ref.WeakReference<ClassLoader> loader)
        throws InterruptedException {
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (loader.get() != null && System.nanoTime() < deadline) {
        System.gc();
        Thread.sleep(20);
      }
      return loader.get() == null;
    }
  }

  /** An application's inheritable request context (Spring's, an MDC adapter's). */
  private static final InheritableThreadLocal<Object> REQUEST_CONTEXT =
      new InheritableThreadLocal<>();

  /** Runs a task from a frame of its own class; {@link ThrowawayLoader} defines a copy of it. */
  public static final class LoaderFrame implements Consumer<Runnable> {

    /** For the reflective instantiation of the throwaway loader's copy. */
    public LoaderFrame() {}

    @Override
    public void accept(final Runnable task) {
      task.run();
    }

    /** An instance of the copy of this class that {@code loader} defines. */
    @SuppressWarnings("unchecked") // the copy implements the parent loader's Consumer
    static Consumer<Runnable> in(final ClassLoader loader) throws ReflectiveOperationException {
      return (Consumer<Runnable>)
          loader.loadClass(LoaderFrame.class.getName()).getDeclaredConstructor().newInstance();
    }
  }

  /**
   * A loader that defines its own copy of {@link LoaderFrame}, as an application's (a dev-mode
   * restart's) loader defines the application's classes, and delegates everything else.
   */
  private static final class ThrowawayLoader extends ClassLoader {

    ThrowawayLoader() {
      super(StressorEdgeBehaviourTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (!name.equals(LoaderFrame.class.getName())) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        final Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        try (java.io.InputStream in =
            getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          if (in == null) {
            throw new ClassNotFoundException(name);
          }
          final byte[] bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length);
        } catch (final java.io.IOException unreadable) {
          throw new ClassNotFoundException(name, unreadable);
        }
      }
    }
  }
}
