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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HttpClientBenchmark {

  private static final String URL = "https://example.com/api/v1/orders";

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
      runtime.activate(
          ChaosScenario.builder("http-no-match")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.httpClient(
                      Set.of(OperationType.HTTP_CLIENT_SEND),
                      NamePattern.regex("https://other\\.example\\.net/.*")))
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

  @State(Scope.Benchmark)
  public static class OneMatchNoEffectState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("http-match-no-effect")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.httpClient(
                      Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(0)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 0.0d, 0, null, null, null, 1L, false))
              .build());
      dispatcher = runtime.dispatcher();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  @State(Scope.Benchmark)
  public static class SessionMissState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      var session = runtime.openSession("http-bench-session");
      session.activate(
          ChaosScenario.builder("http-session-miss")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(
                  ChaosSelector.httpClient(
                      Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(0)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      dispatcher = runtime.dispatcher();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  @State(Scope.Benchmark)
  public static class TenScenariosState {
    ChaosRuntime runtime;
    ChaosDispatcher dispatcher;

    @Setup(Level.Trial)
    public void setup() {
      runtime = new ChaosRuntime();
      for (int i = 0; i < 9; i++) {
        runtime.activate(
            ChaosScenario.builder("http-no-match-" + i)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    ChaosSelector.httpClient(
                        Set.of(OperationType.HTTP_CLIENT_SEND_ASYNC), NamePattern.any()))
                .effect(ChaosEffect.delay(Duration.ofMillis(0)))
                .activationPolicy(ActivationPolicy.always())
                .build());
      }
      runtime.activate(
          ChaosScenario.builder("http-match")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.httpClient(
                      Set.of(OperationType.HTTP_CLIENT_SEND), NamePattern.any()))
              .effect(ChaosEffect.delay(Duration.ofMillis(0)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 0.0d, 0, null, null, null, 1L, false))
              .build());
      dispatcher = runtime.dispatcher();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      runtime.close();
    }
  }

  @Benchmark
  public void baseline_noAgent(Blackhole bh) {
    bh.consume(URL.length());
  }

  @Benchmark
  public void agentInstalled_zeroScenarios(ZeroScenariosState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND));
  }

  @Benchmark
  public void agentInstalled_oneScenario_noMatch(OneScenarioNoMatchState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND));
  }

  @Benchmark
  public void agentInstalled_oneMatch_noEffect(OneMatchNoEffectState state, Blackhole bh)
      throws Throwable {
    bh.consume(state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND));
  }

  @Benchmark
  public void sessionIdMiss(SessionMissState state, Blackhole bh) throws Throwable {
    bh.consume(state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND));
  }

  @Benchmark
  public void tenScenarios_oneMatch(TenScenariosState state, Blackhole bh) throws Throwable {
    bh.consume(state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND));
  }
}
