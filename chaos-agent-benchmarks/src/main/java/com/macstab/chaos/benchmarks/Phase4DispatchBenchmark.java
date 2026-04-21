package com.macstab.chaos.benchmarks;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.core.ChaosDispatcher;
import com.macstab.chaos.core.ChaosRuntime;
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

  // ── states ───────────────────────────────────────────────────────────────

  @State(Scope.Benchmark)
  public static class ZeroScenariosState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      dispatcher = runtime.dispatcher();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  @State(Scope.Benchmark)
  public static class OneScenarioNoMatchState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

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
      dispatcher.beforeThreadSleep(1L);
      dispatcher.beforeDnsResolve("bench.internal");
      dispatcher.beforeSslHandshake(null);
      dispatcher.beforeFileIo("FILE_IO_READ", null);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  // ── Thread.sleep ─────────────────────────────────────────────────────────

  @Benchmark
  public boolean threadSleep_zeroScenarios(ZeroScenariosState s) throws Throwable {
    return s.dispatcher.beforeThreadSleep(1L);
  }

  @Benchmark
  public boolean threadSleep_oneScenarioNoMatch(OneScenarioNoMatchState s) throws Throwable {
    return s.dispatcher.beforeThreadSleep(1L);
  }

  @Benchmark
  public boolean threadSleep_fourScenariosExhausted(FourScenariosExhaustedState s)
      throws Throwable {
    return s.dispatcher.beforeThreadSleep(1L);
  }

  // ── DNS resolve ──────────────────────────────────────────────────────────

  @Benchmark
  public void dnsResolve_zeroScenarios(ZeroScenariosState s, Blackhole bh) throws Throwable {
    s.dispatcher.beforeDnsResolve("api.example.com");
    bh.consume(s);
  }

  @Benchmark
  public void dnsResolve_oneScenarioNoMatch(OneScenarioNoMatchState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeDnsResolve("api.example.com");
    bh.consume(s);
  }

  @Benchmark
  public void dnsResolve_fourScenariosExhausted(FourScenariosExhaustedState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeDnsResolve("api.example.com");
    bh.consume(s);
  }

  // ── SSL/TLS handshake ────────────────────────────────────────────────────

  @Benchmark
  public void sslHandshake_zeroScenarios(ZeroScenariosState s, Blackhole bh) throws Throwable {
    s.dispatcher.beforeSslHandshake(null);
    bh.consume(s);
  }

  @Benchmark
  public void sslHandshake_oneScenarioNoMatch(OneScenarioNoMatchState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeSslHandshake(null);
    bh.consume(s);
  }

  @Benchmark
  public void sslHandshake_fourScenariosExhausted(FourScenariosExhaustedState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeSslHandshake(null);
    bh.consume(s);
  }

  // ── File I/O read ─────────────────────────────────────────────────────────

  @Benchmark
  public void fileIoRead_zeroScenarios(ZeroScenariosState s, Blackhole bh) throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_READ", null);
    bh.consume(s);
  }

  @Benchmark
  public void fileIoRead_oneScenarioNoMatch(OneScenarioNoMatchState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_READ", null);
    bh.consume(s);
  }

  @Benchmark
  public void fileIoRead_fourScenariosExhausted(FourScenariosExhaustedState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_READ", null);
    bh.consume(s);
  }

  // ── File I/O write ────────────────────────────────────────────────────────

  @Benchmark
  public void fileIoWrite_zeroScenarios(ZeroScenariosState s, Blackhole bh) throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_WRITE", null);
    bh.consume(s);
  }

  @Benchmark
  public void fileIoWrite_oneScenarioNoMatch(OneScenarioNoMatchState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_WRITE", null);
    bh.consume(s);
  }

  @Benchmark
  public void fileIoWrite_fourScenariosExhausted(FourScenariosExhaustedState s, Blackhole bh)
      throws Throwable {
    s.dispatcher.beforeFileIo("FILE_IO_WRITE", null);
    bh.consume(s);
  }
}
