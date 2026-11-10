package com.macstab.chaos.jvm.benchmarks;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * The dispatch cost behind woven advice per intercepted call, measured by calling {@link
 * BootstrapDispatcher} directly (nothing is woven, so the advice's own bytecode is not included):
 * the reentrancy guard, the method-handle hop into the agent, the operation index and, when a
 * scenario's selector matches, the policy checks. To compare a change to the dispatch path, run it
 * for both builds on the same machine ({@code docs/benchmarks.md} §8.3).
 *
 * <ul>
 *   <li>{@code none}: no scenario is active, the cost every intercepted call pays once its hook
 *       group is woven.
 *   <li>{@code otherOperation}: scenarios exist, but for an operation no benchmark calls; the index
 *       lookup rejects the call.
 *   <li>{@code matchedNotYetActive}: each call's operation has a scenario whose selector matches
 *       but whose {@code activeFrom} lies a day ahead, so every call runs the full evaluation up to
 *       the wall-clock window and changes nothing.
 * </ul>
 *
 * <p>Run with {@code -prof gc} to see the allocation rate; {@code none} and {@code otherOperation}
 * allocate nothing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BridgeDispatchBenchmark {

  /** Creates a new benchmark instance (invoked reflectively by JMH). */
  public BridgeDispatchBenchmark() {}

  /** Which scenarios are active while measuring. */
  @SuppressWarnings("NullAway.Init") // set by JMH before @Setup
  @Param({"none", "otherOperation", "matchedNotYetActive"})
  public String scenarios;

  @SuppressWarnings("UnnecessaryLambda") // one shared task, so the benchmark measures dispatch
  private static final Runnable TASK = () -> {};

  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);

  private static final java.nio.ByteBuffer BUFFER = java.nio.ByteBuffer.allocate(8192);

  private ChaosRuntime runtime;
  private AutoCloseable connection;

  /**
   * Connects a fresh runtime to the dispatcher and activates the scenarios of {@link #scenarios}.
   *
   * @throws Exception if the dispatcher cannot be connected
   */
  @Setup(Level.Trial)
  public void setUp() throws Exception {
    runtime = new ChaosRuntime();
    connection = JdkInstrumentationInstaller.connectDispatcher(runtime);
    switch (scenarios) {
      case "none" -> {}
      case "otherOperation" ->
          runtime.activate(
              scenario(
                  "other",
                  ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE),
                  ChaosEffect.delay(Duration.ofMillis(1))));
      case "matchedNotYetActive" -> {
        runtime.activate(
            scenario(
                "clock",
                ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)),
                new ChaosEffect.ClockSkewEffect(
                    Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED)));
        runtime.activate(
            scenario(
                "sleep",
                ChaosSelector.thread(
                    Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
                ChaosEffect.delay(Duration.ofMillis(1))));
        runtime.activate(
            scenario(
                "file",
                ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
                ChaosEffect.delay(Duration.ofMillis(1))));
        runtime.activate(
            scenario(
                "socket",
                ChaosSelector.network(Set.of(OperationType.SOCKET_READ)),
                ChaosEffect.delay(Duration.ofMillis(1))));
        runtime.activate(
            scenario(
                "submit",
                ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
                ChaosEffect.delay(Duration.ofMillis(1))));
        runtime.activate(
            scenario(
                "clock-read",
                ChaosSelector.jvmRuntime(Set.of(OperationType.CLOCK_READ)),
                new ChaosEffect.ClockSkewEffect(
                    Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED)));
        runtime.activate(
            scenario(
                "close",
                ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_CLOSE)),
                ChaosEffect.delay(Duration.ofMillis(1))));
        runtime.activate(
            scenario(
                "datagram",
                ChaosSelector.network(Set.of(OperationType.DATAGRAM_SEND)),
                ChaosEffect.delay(Duration.ofMillis(1))));
      }
      default -> throw new IllegalArgumentException("unknown scenarios: " + scenarios);
    }
  }

  /**
   * Disconnects the dispatcher and closes the runtime.
   *
   * @throws Exception if disconnecting fails
   */
  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    connection.close();
    runtime.close();
  }

  /**
   * {@code System.currentTimeMillis()} advice.
   *
   * @return the adjusted clock value
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public long clockMillis() throws Throwable {
    return BootstrapDispatcher.adjustClockMillis(1_700_000_000_000L);
  }

  /**
   * {@code Thread.sleep} advice.
   *
   * @return whether the sleep is suppressed
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public boolean threadSleep() throws Throwable {
    return BootstrapDispatcher.beforeThreadSleep(1L);
  }

  /**
   * {@code FileInputStream.read} advice.
   *
   * @return the byte-count code
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public int fileRead() throws Throwable {
    return BootstrapDispatcher.beforeFileIo("FILE_IO_READ", this, "/tmp/bench.dat", 8192);
  }

  /**
   * Socket input-stream read advice.
   *
   * @return the byte-count code
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public int socketRead() throws Throwable {
    return BootstrapDispatcher.beforeSocketRead(this, 8192, null, 443, 0);
  }

  /**
   * {@code ThreadPoolExecutor.execute} advice.
   *
   * @return the possibly wrapped task
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public Runnable executorSubmit() throws Throwable {
    return BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, TASK);
  }

  /**
   * System {@code Clock.instant()} advice ({@code CLOCK_READ}): every {@code java.time} read
   * without an operation of its own.
   *
   * @return the adjusted instant
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public Instant clockRead() throws Throwable {
    return BootstrapDispatcher.adjustClockInstant(NOW);
  }

  /**
   * System {@code Clock.millis()} advice ({@code CLOCK_READ}).
   *
   * @return the adjusted milliseconds
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public long clockReadMillis() throws Throwable {
    return BootstrapDispatcher.adjustClockReadMillis(1_700_000_000_000L);
  }

  /**
   * Stream and channel {@code close()} advice ({@code FILE_IO_CLOSE}).
   *
   * @return the byte-count code
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public int fileClose() throws Throwable {
    return BootstrapDispatcher.beforeFileIo("FILE_IO_CLOSE", this, "/tmp/bench.dat", -1);
  }

  /**
   * {@code SocketChannel.read(ByteBuffer)} advice ({@code SOCKET_READ} of a channel).
   *
   * @return the buffer shaping, {@code null} when unchanged
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public @Nullable Object socketChannelRead() throws Throwable {
    return BootstrapDispatcher.beforeSocketChannelRead(this, BUFFER);
  }

  /**
   * {@code DatagramChannel.send} advice ({@code DATAGRAM_SEND}).
   *
   * @return {@code 0} to send
   * @throws Throwable never, with these scenarios
   */
  @Benchmark
  public int datagramSend() throws Throwable {
    return BootstrapDispatcher.beforeDatagramSend(this, BUFFER, null);
  }

  private static ChaosScenario scenario(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(effect)
        .activationPolicy(
            ActivationPolicy.builder().activeFrom(Instant.now().plus(Duration.ofDays(1))).build())
        .build();
  }
}
