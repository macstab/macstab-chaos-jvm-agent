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
import java.util.concurrent.Executor;
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
 * JMH benchmarks measuring core {@link ChaosRuntime} dispatch overhead under a range of scenario
 * loads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ChaosRuntimeBenchmark {

  /** Creates a new benchmark instance (invoked reflectively by JMH). */
  public ChaosRuntimeBenchmark() {}

  private static final Executor DUMMY_EXECUTOR = Runnable::run;
  private static final Runnable DUMMY_TASK = () -> {};
  private static final Duration ZERO_DELAY = Duration.ofMillis(0);
  private static final ActivationPolicy ONE_SHOT_POLICY =
      new ActivationPolicy(
          ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, 1L, false);
  private static final String BENCHMARK_SQL = "SELECT 1";
  private static final int NON_MATCHING_SCENARIO_COUNT = 9;

  /** Baseline state used to measure raw executor throughput without the chaos agent installed. */
  @State(Scope.Benchmark)
  public static class NoAgentState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public NoAgentState() {}

    Executor executor = DUMMY_EXECUTOR;
  }

  /** State with the runtime installed but no active scenarios — measures pure dispatch overhead. */
  @State(Scope.Benchmark)
  public static class ZeroScenariosState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public ZeroScenariosState() {}

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

  /** State with a single active scenario whose selector never matches the benchmark call. */
  @State(Scope.Benchmark)
  public static class OneScenarioNoMatchState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public OneScenarioNoMatchState() {}

    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers the non-matching scenario before the trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("bench-no-match")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE),
                      NamePattern.regex("SELECT\\s+\\*\\s+FROM\\s+no_match_table")))
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

  /** State with a scenario that matches but applies a zero-delay no-op effect. */
  @State(Scope.Benchmark)
  public static class OneMatchNoEffectState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public OneMatchNoEffectState() {}

    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers the matching no-effect scenario before the trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("bench-match-no-effect")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.any()))
              .effect(ChaosEffect.delay(ZERO_DELAY))
              .activationPolicy(ONE_SHOT_POLICY)
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
   * State with a session-scoped scenario but no active session on the calling thread — measures
   * session-miss path.
   */
  @State(Scope.Benchmark)
  public static class SessionMissState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public SessionMissState() {}

    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers the session-scoped scenario before the trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      final var session = runtime.openSession("bench-session");
      session.activate(
          ChaosScenario.builder("bench-session-miss")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.any()))
              .effect(ChaosEffect.delay(ZERO_DELAY))
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
   * State with nine non-matching scenarios plus one matching scenario to measure registry scan
   * cost.
   */
  @State(Scope.Benchmark)
  public static class TenScenariosState {
    /** Creates a new state instance (invoked reflectively by JMH). */
    public TenScenariosState() {}

    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    /** Registers the mix of non-matching and matching scenarios before the trial. */
    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      for (int i = 0; i < NON_MATCHING_SCENARIO_COUNT; i++) {
        runtime.activate(
            ChaosScenario.builder("bench-no-match-" + i)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    new ChaosSelector.JdbcSelector(
                        Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.any()))
                .effect(ChaosEffect.delay(ZERO_DELAY))
                .activationPolicy(ActivationPolicy.always())
                .build());
      }
      runtime.activate(
          ChaosScenario.builder("bench-match")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.JdbcSelector(
                      Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.any()))
              .effect(ChaosEffect.delay(ZERO_DELAY))
              .activationPolicy(ONE_SHOT_POLICY)
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
   * Baseline benchmark: plain executor dispatch without the chaos agent.
   *
   * @param state the benchmark state holding the plain executor
   * @param bh the JMH blackhole used to consume results
   */
  @Benchmark
  public void baseline_noAgent(NoAgentState state, Blackhole bh) {
    state.executor.execute(DUMMY_TASK);
    bh.consume(state.executor);
  }

  /**
   * Measures dispatcher overhead when the runtime is installed but has no active scenarios.
   *
   * @param state the benchmark state holding the runtime and dispatcher
   * @param bh the JMH blackhole used to consume results
   * @throws Throwable if the dispatcher surfaces an unexpected failure
   */
  @Benchmark
  public void agentInstalled_zeroScenarios(ZeroScenariosState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute(BENCHMARK_SQL));
  }

  /**
   * Measures dispatcher overhead when exactly one non-matching scenario is active.
   *
   * @param state the benchmark state holding the runtime and dispatcher
   * @param bh the JMH blackhole used to consume results
   * @throws Throwable if the dispatcher surfaces an unexpected failure
   */
  @Benchmark
  public void agentInstalled_oneScenario_noMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute(BENCHMARK_SQL));
  }

  /**
   * Measures dispatcher overhead when exactly one matching scenario applies a no-op effect.
   *
   * @param state the benchmark state holding the runtime and dispatcher
   * @param bh the JMH blackhole used to consume results
   * @throws Throwable if the dispatcher surfaces an unexpected failure
   */
  @Benchmark
  public void agentInstalled_oneMatch_noEffect(OneMatchNoEffectState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute(BENCHMARK_SQL));
  }

  /**
   * Measures dispatcher overhead when a session-scoped scenario does not match the calling thread's
   * session.
   *
   * @param state the benchmark state holding the runtime and dispatcher
   * @param bh the JMH blackhole used to consume results
   * @throws Throwable if the dispatcher surfaces an unexpected failure
   */
  @Benchmark
  public void sessionIdMiss(SessionMissState state, Blackhole bh) throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute(BENCHMARK_SQL));
  }

  /**
   * Measures dispatcher overhead when ten scenarios are registered but only one matches.
   *
   * @param state the benchmark state holding the runtime and dispatcher
   * @param bh the JMH blackhole used to consume results
   * @throws Throwable if the dispatcher surfaces an unexpected failure
   */
  @Benchmark
  public void tenScenarios_oneMatch(TenScenariosState state, Blackhole bh) throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute(BENCHMARK_SQL));
  }
}
