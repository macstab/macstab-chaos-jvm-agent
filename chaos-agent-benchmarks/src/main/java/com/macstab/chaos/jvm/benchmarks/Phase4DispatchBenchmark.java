package com.macstab.chaos.jvm.benchmarks;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosDispatcher;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmarks for the four Phase 4 dispatch paths: Thread.sleep suppression, DNS resolution
 * interception, SSL/TLS handshake interception, and File I/O interception.
 *
 * <h2>What is being measured</h2>
 *
 * <p>Each benchmark measures only the <em>dispatch overhead</em> — the cost of the framework
 * plumbing (registry lookup, selector evaluation, activation-policy check) <strong>without</strong>
 * the injected chaos delay, so results reflect what every request pays even when no chaos fires.
 *
 * <h2>Scenario states</h2>
 *
 * <ul>
 *   <li>{@code zeroScenarios} — the runtime is loaded but empty; this is the theoretical lower
 *       bound for a live agent.
 *   <li>{@code oneScenarioNoMatch} — one active scenario whose selector targets a different
 *       operation type, exercising the selector's hot-miss path.
 *   <li>{@code fourScenariosOnePerType} — one scenario per Phase 4 operation type, all matching but
 *       with {@code maxApplications=1} consumed during setup, so the measurement loop sees an
 *       "exhausted" scenario — full selector evaluation plus expiry check.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Phase4DispatchBenchmark {

  private static final Duration ZERO_DELAY = Duration.ofMillis(0);
  private static final ActivationPolicy ONE_SHOT_POLICY =
      new ActivationPolicy(
          ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 1L, false);

  private static final long SLEEP_DURATION_MILLIS = 1L;
  private static final String DNS_HOSTNAME = "api.example.com";
  private static final String SETUP_DNS_HOSTNAME = "bench.internal";
  private static final String FILE_IO_READ_OPERATION = "FILE_IO_READ";
  private static final String FILE_IO_WRITE_OPERATION = "FILE_IO_WRITE";

  // ── states ───────────────────────────────────────────────────────────────

  /** State with the runtime installed but no active scenarios — measures pure dispatch overhead. */
  @State(Scope.Benchmark)
  public static class ZeroScenariosState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Initialises the runtime and dispatcher before the measurement trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      dispatcher = runtime.dispatcher();
    }

    /** Closes the runtime after the measurement trial. */
    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  /**
   * State with a single non-matching JDBC scenario active, exercising the selector hot-miss path.
   */
  @State(Scope.Benchmark)
  public static class OneScenarioNoMatchState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers the non-matching scenario before the trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      // A JDBC scenario — does not match any Phase 4 dispatch path.
      runtime.activate(
          ChaosScenario.builder("bench-jdbc-nomatch")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      dispatcher = runtime.dispatcher();
    }

    /** Closes the runtime after the measurement trial. */
    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  /**
   * Four Phase 4 scenarios, each matching one operation type, activated with {@code
   * maxApplications=1} and then immediately consumed in setup so that the benchmark loop measures
   * full selector evaluation against an already-exhausted scenario (the "evaluated but inactive"
   * hot path in production).
   */
  @State(Scope.Benchmark)
  public static class FourScenariosExhaustedState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers and immediately consumes the four matching scenarios before the trial. */
    @Setup(Level.Trial)
    public void setup() throws Throwable {
      runtime = new ChaosRuntime();

      runtime.activate(
          ChaosScenario.builder("bench-sleep")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.thread(
                      Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
              .effect(ChaosEffect.suppress())
              .activationPolicy(ONE_SHOT_POLICY)
              .build());

      runtime.activate(
          ChaosScenario.builder("bench-dns")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE), NamePattern.any()))
              .effect(ChaosEffect.delay(ZERO_DELAY))
              .activationPolicy(ONE_SHOT_POLICY)
              .build());

      runtime.activate(
          ChaosScenario.builder("bench-ssl")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)))
              .effect(ChaosEffect.delay(ZERO_DELAY))
              .activationPolicy(ONE_SHOT_POLICY)
              .build());

      runtime.activate(
          ChaosScenario.builder("bench-fileread")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
              .effect(ChaosEffect.delay(ZERO_DELAY))
              .activationPolicy(ONE_SHOT_POLICY)
              .build());

      dispatcher = runtime.dispatcher();

      // Consume each scenario once so they are exhausted for the measurement loop.
      dispatcher.beforeThreadSleep(SLEEP_DURATION_MILLIS);
      dispatcher.beforeDnsResolve(SETUP_DNS_HOSTNAME);
      dispatcher.beforeSslHandshake(null);
      dispatcher.beforeFileIo(FILE_IO_READ_OPERATION, null);
    }

    /** Closes the runtime after the measurement trial. */
    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  // ── Thread.sleep ─────────────────────────────────────────────────────────

  /** Measures {@code beforeThreadSleep} dispatch overhead with zero active scenarios. */
  @Benchmark
  public boolean threadSleep_zeroScenarios(ZeroScenariosState state) throws Throwable {
    return state.dispatcher.beforeThreadSleep(SLEEP_DURATION_MILLIS);
  }

  /** Measures {@code beforeThreadSleep} dispatch overhead with one non-matching scenario active. */
  @Benchmark
  public boolean threadSleep_oneScenarioNoMatch(OneScenarioNoMatchState state) throws Throwable {
    return state.dispatcher.beforeThreadSleep(SLEEP_DURATION_MILLIS);
  }

  /**
   * Measures {@code beforeThreadSleep} dispatch overhead against four exhausted matching scenarios.
   */
  @Benchmark
  public boolean threadSleep_fourScenariosExhausted(FourScenariosExhaustedState state)
      throws Throwable {
    return state.dispatcher.beforeThreadSleep(SLEEP_DURATION_MILLIS);
  }

  // ── DNS resolve ──────────────────────────────────────────────────────────

  /** Measures {@code beforeDnsResolve} dispatch overhead with zero active scenarios. */
  @Benchmark
  public void dnsResolve_zeroScenarios(ZeroScenariosState state, Blackhole bh) throws Throwable {
    state.dispatcher.beforeDnsResolve(DNS_HOSTNAME);
    bh.consume(state);
  }

  /** Measures {@code beforeDnsResolve} dispatch overhead with one non-matching scenario active. */
  @Benchmark
  public void dnsResolve_oneScenarioNoMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeDnsResolve(DNS_HOSTNAME);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeDnsResolve} dispatch overhead against four exhausted matching scenarios.
   */
  @Benchmark
  public void dnsResolve_fourScenariosExhausted(FourScenariosExhaustedState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeDnsResolve(DNS_HOSTNAME);
    bh.consume(state);
  }

  // ── SSL/TLS handshake ────────────────────────────────────────────────────

  /** Measures {@code beforeSslHandshake} dispatch overhead with zero active scenarios. */
  @Benchmark
  public void sslHandshake_zeroScenarios(ZeroScenariosState state, Blackhole bh) throws Throwable {
    state.dispatcher.beforeSslHandshake(null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeSslHandshake} dispatch overhead with one non-matching scenario active.
   */
  @Benchmark
  public void sslHandshake_oneScenarioNoMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeSslHandshake(null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeSslHandshake} dispatch overhead against four exhausted matching
   * scenarios.
   */
  @Benchmark
  public void sslHandshake_fourScenariosExhausted(FourScenariosExhaustedState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeSslHandshake(null);
    bh.consume(state);
  }

  // ── File I/O read ─────────────────────────────────────────────────────────

  /** Measures {@code beforeFileIo(FILE_IO_READ)} dispatch overhead with zero active scenarios. */
  @Benchmark
  public void fileIoRead_zeroScenarios(ZeroScenariosState state, Blackhole bh) throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_READ_OPERATION, null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeFileIo(FILE_IO_READ)} dispatch overhead with one non-matching scenario
   * active.
   */
  @Benchmark
  public void fileIoRead_oneScenarioNoMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_READ_OPERATION, null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeFileIo(FILE_IO_READ)} dispatch overhead against four exhausted matching
   * scenarios.
   */
  @Benchmark
  public void fileIoRead_fourScenariosExhausted(FourScenariosExhaustedState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_READ_OPERATION, null);
    bh.consume(state);
  }

  // ── File I/O write ────────────────────────────────────────────────────────

  /** Measures {@code beforeFileIo(FILE_IO_WRITE)} dispatch overhead with zero active scenarios. */
  @Benchmark
  public void fileIoWrite_zeroScenarios(ZeroScenariosState state, Blackhole bh) throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_WRITE_OPERATION, null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeFileIo(FILE_IO_WRITE)} dispatch overhead with one non-matching scenario
   * active.
   */
  @Benchmark
  public void fileIoWrite_oneScenarioNoMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_WRITE_OPERATION, null);
    bh.consume(state);
  }

  /**
   * Measures {@code beforeFileIo(FILE_IO_WRITE)} dispatch overhead against four exhausted matching
   * scenarios.
   */
  @Benchmark
  public void fileIoWrite_fourScenariosExhausted(FourScenariosExhaustedState state, Blackhole bh)
      throws Throwable {
    state.dispatcher.beforeFileIo(FILE_IO_WRITE_OPERATION, null);
    bh.consume(state);
  }
}
