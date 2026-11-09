package com.macstab.chaos.jvm.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosMeterBinder")
class ChaosMeterBinderTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("counts applications per scenario and gauges scenarios per state")
  void countsAndGauges() throws Exception {
    new ChaosMeterBinder(runtime).bindTo(registry);
    runtime.activate(
        ChaosScenario.builder("metered")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    // Events are counted asynchronously; wait for both applications. The delay is the last meter
    // an application updates, so once it holds both, every other meter does too.
    await().atMost(Duration.ofSeconds(5)).until(() -> delayCount() >= 2);

    assertThat(
            registry
                .get("chaos.events")
                .tags("type", "applied", "scenario", "metered")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(registry.get("chaos.scenarios").tags("state", "active").gauge().value())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get("chaos.applications")
                .tags("scenario", "metered", "operation", "executor_submit", "effect", "delay")
                .counter()
                .count())
        .isEqualTo(2.0);
    final io.micrometer.core.instrument.Timer delay =
        registry.get("chaos.injected.delay").tags("scenario", "metered").timer();
    assertThat(delay.count()).isEqualTo(2L);
    assertThat(delay.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(2.0);
    assertThat(registry.get("chaos.hooks.woven").gauge().value()).isGreaterThanOrEqualTo(0.0);
    assertThat(registry.get("chaos.listener.events.dropped").functionCounter().count()).isZero();
  }

  @Test
  @DisplayName("uses one tag key set per meter name, so Prometheus accepts every event type")
  void consistentTagKeys() throws Exception {
    final io.micrometer.core.instrument.MeterRegistry strict =
        new SimpleMeterRegistry() {
          private final java.util.Map<String, java.util.Set<String>> keys =
              new java.util.concurrent.ConcurrentHashMap<>();

          @Override
          protected io.micrometer.core.instrument.Counter newCounter(
              final io.micrometer.core.instrument.Meter.Id id) {
            final java.util.Set<String> tagKeys =
                id.getTags().stream()
                    .map(io.micrometer.core.instrument.Tag::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            final java.util.Set<String> previous = keys.putIfAbsent(id.getName(), tagKeys);
            if (previous != null && !previous.equals(tagKeys)) {
              throw new IllegalArgumentException(id.getName() + " tag keys " + tagKeys);
            }
            return super.newCounter(id);
          }
        };
    final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
    binder.bindTo(strict);
    runtime.activate(
        ChaosScenario.builder("half")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.builder().probability(0.5).randomSeed(7L).build())
            .build());
    for (int i = 0; i < 20; i++) {
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    }
    await().atMost(Duration.ofSeconds(5)).until(() -> strict.find("chaos.skips").counter() != null);
    binder.close();

    assertThat(strict.get("chaos.events").tags("type", "skipped").counter().count()).isPositive();
    assertThat(strict.get("chaos.events").tags("type", "applied").counter().count()).isPositive();
  }

  @Test
  @DisplayName("counts failed activations by category and stops counting once closed")
  void failuresAndClose() throws Exception {
    final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
    binder.bindTo(registry);
    final ChaosScenario scenario =
        ChaosScenario.builder("twice")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build();
    runtime.activate(scenario);
    try {
      runtime.activate(scenario);
    } catch (final RuntimeException conflict) {
      // the id is taken: the second activation fails
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> registry.find("chaos.activation.failures").counter() != null);
    assertThat(
            registry
                .get("chaos.activation.failures")
                .tags("category", "ACTIVATION_CONFLICT")
                .counter()
                .count())
        .isEqualTo(1.0);

    // Counted while bound; the listener delivers asynchronously.
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    await().atMost(Duration.ofSeconds(5)).until(() -> applications() == 1.0);

    binder.close();
    binder.close();
    // close() removed the listener: this application is never handed to the binder.
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(2))
        .until(() -> applications() == 1.0);
  }

  private double applications() {
    final io.micrometer.core.instrument.Counter counter =
        registry.find("chaos.applications").tags("scenario", "twice").counter();
    return counter == null ? 0.0 : counter.count();
  }

  @Test
  @DisplayName("the series of ended scenarios are retired one scrape later, on the next event")
  void retiresEndedScenarios() {
    new ChaosMeterBinder(runtime).bindTo(registry);
    final java.util.List<com.macstab.chaos.jvm.api.ChaosActivationHandle> handles =
        new java.util.ArrayList<>();
    for (int i = 0; i < 50; i++) {
      handles.add(runtime.activate(scenario("ended-" + i)));
    }
    // Closed (unregistered) and only stopped (still registered as STOPPED) alike.
    for (int i = 0; i < 50; i++) {
      if (i % 2 == 0) {
        handles.get(i).close();
      } else {
        handles.get(i).stop();
      }
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> registry.find("chaos.events").tag("type", "stopped").counters().size() == 50);

    scrape();
    scrape();
    runtime.activate(scenario("later"));

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry
                        .find("chaos.events")
                        .tag("type", "registered")
                        .tag("scenario", "later")
                        .counter()
                    != null);
    for (int i = 0; i < 50; i++) {
      assertThat(registry.find("chaos.events").tag("scenario", "ended-" + i).counter())
          .as("ended-" + i)
          .isNull();
    }
  }

  @Test
  @DisplayName("a series shared by an id that is still live in another scope is kept")
  void keepsSharedSeries() {
    new ChaosMeterBinder(runtime).bindTo(registry);
    runtime.activate(scenario("shared"));
    try (com.macstab.chaos.jvm.api.ChaosSession session = runtime.openSession("other")) {
      session.activate(
          ChaosScenario.builder("shared")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build());
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry
                        .find("chaos.events")
                        .tags("type", "stopped", "scenario", "shared")
                        .counter()
                    != null);

    scrape();
    scrape();
    runtime.activate(scenario("later"));

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry
                        .find("chaos.events")
                        .tag("type", "registered")
                        .tag("scenario", "later")
                        .counter()
                    != null);
    assertThat(
            registry.find("chaos.events").tags("type", "started", "scenario", "shared").counter())
        .isNotNull()
        .satisfies(counter -> assertThat(counter.count()).isEqualTo(2.0));
  }

  @Test
  @DisplayName("refused activations are counted by category, without a series per scenario")
  void refusalsWithoutScenarioTag() {
    new ChaosMeterBinder(runtime).bindTo(registry);
    for (int i = 0; i < 50; i++) {
      final ChaosScenario refused =
          ChaosScenario.builder("refused-" + i)
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      try {
        runtime.activate(refused);
      } catch (final RuntimeException expected) {
        // a session-scoped scenario outside a session is refused
      }
    }

    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              final io.micrometer.core.instrument.Counter failures =
                  registry
                      .find("chaos.activation.failures")
                      .tag("category", "INVALID_CONFIGURATION")
                      .counter();
              return failures != null && failures.count() == 50.0;
            });
    assertThat(registry.find("chaos.activation.failures").tagKeys("scenario").counters()).isEmpty();
  }

  @Test
  @DisplayName("an ended scenario not scraped yet keeps its series until close() retires it")
  void closeRetiresUnscraped() {
    final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
    binder.bindTo(registry);
    runtime.activate(
        ChaosScenario.builder("waiting")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(
                ActivationPolicy.builder().startMode(ActivationPolicy.StartMode.MANUAL).build())
            .build());
    runtime.activate(scenario("early")).close();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry.find("chaos.events").tags("type", "stopped", "scenario", "early").counter()
                    != null);
    scrape();
    runtime.activate(scenario("gone")).close();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry.find("chaos.events").tags("type", "stopped", "scenario", "gone").counter()
                    != null);
    // Two scrapes since early ended, one since gone ended.
    scrape();
    runtime.activate(scenario("later"));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                registry
                        .find("chaos.events")
                        .tags("type", "registered", "scenario", "later")
                        .counter()
                    != null);
    assertThat(registry.find("chaos.events").tag("scenario", "early").counter())
        .as("scraped since it ended")
        .isNull();
    assertThat(registry.find("chaos.events").tag("scenario", "gone").counter())
        .as("not scraped since it ended")
        .isNotNull();

    binder.close();

    assertThat(registry.find("chaos.events").tag("scenario", "gone").counter()).isNull();
    assertThat(registry.find("chaos.events").tag("scenario", "waiting").counter())
        .as("still registered")
        .isNotNull();
  }

  @Test
  @DisplayName("a meter the registry refuses to remove is logged once")
  void removalFailureLoggedOnce() {
    final io.micrometer.core.instrument.MeterRegistry refusing =
        new SimpleMeterRegistry() {
          @Override
          public io.micrometer.core.instrument.Meter remove(
              final io.micrometer.core.instrument.Meter meter) {
            throw new IllegalStateException("refused");
          }
        };
    final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ChaosMeterBinder.class.getName());
    final java.util.List<java.util.logging.LogRecord> logged =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.logging.Handler capture =
        new java.util.logging.Handler() {
          @Override
          public void publish(final java.util.logging.LogRecord record) {
            logged.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try {
      final ChaosMeterBinder binder = new ChaosMeterBinder(runtime);
      binder.bindTo(refusing);
      runtime.activate(scenario("stuck")).close();
      await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () ->
                  refusing
                          .find("chaos.events")
                          .tags("type", "stopped", "scenario", "stuck")
                          .counter()
                      != null);

      binder.close();
    } finally {
      logger.removeHandler(capture);
    }

    assertThat(logged)
        .singleElement()
        .satisfies(
            record -> assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING));
  }

  private void scrape() {
    registry.find("chaos.scenarios").gauges().forEach(io.micrometer.core.instrument.Gauge::value);
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }

  private long delayCount() {
    final io.micrometer.core.instrument.Timer timer =
        registry.find("chaos.injected.delay").tags("scenario", "metered").timer();
    return timer == null ? 0L : timer.count();
  }
}
