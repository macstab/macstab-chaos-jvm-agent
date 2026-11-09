package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A failing listener or sink never escapes the bus, and every event type is logged. */
@DisplayName("ObservabilityBus isolation")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class ObservabilityBusIsolationTest {

  private interface CloseableListener extends ChaosEventListener, AutoCloseable {}

  @Test
  @DisplayName("a lifecycle listener receives every event but does not count as an observer")
  void lifecycleListenerIsNoObserver() throws Exception {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final List<ChaosEvent> received = new CopyOnWriteArrayList<>();
    final ChaosEventListener lifecycle = received::add;
    final ChaosEventListener observer = event -> {};

    bus.addLifecycleListener(lifecycle);
    assertThat(bus.hasListeners()).isFalse();
    bus.publish(ChaosEvent.Type.APPLIED, "s1", "applied", Map.of());
    assertThat(received).extracting(ChaosEvent::scenarioId).containsExactly("s1");

    bus.addListener(observer);
    assertThat(bus.hasListeners()).isTrue();
    assertThat(bus.removeListener(observer)).isTrue();
    assertThat(bus.hasListeners()).isFalse();
    assertThat(bus.removeListener(lifecycle)).isTrue();
    assertThat(bus.removeListener(lifecycle)).isFalse();

    bus.addListener(observer);
    bus.close();
    assertThat(bus.hasListeners()).as("after close").isFalse();
  }

  @Test
  @DisplayName("a listener that throws is logged and the next listener still receives the event")
  void throwingListenerIsIsolated() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final List<ChaosEvent> received = new CopyOnWriteArrayList<>();
    bus.addListener(
        event -> {
          throw new IllegalStateException("listener broke");
        });
    bus.addListener(received::add);
    try (LogCapture log = LogCapture.agent()) {
      bus.publish(ChaosEvent.Type.APPLIED, "s1", "applied", Map.of());
      assertThat(received).extracting(ChaosEvent::scenarioId).containsExactly("s1");
      assertThat(log.messages())
          .anyMatch(
              message ->
                  message.startsWith(
                          "ChaosEventListener threw during publish; delivery continues. event=APPLIED"
                              + " scenarioId=s1 listener=")
                      && message.endsWith("error=java.lang.IllegalStateException: listener broke"));
    }
  }

  @Test
  @DisplayName("an asynchronous listener that throws is logged and keeps receiving events")
  void throwingAsyncListenerKeepsDelivering() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final List<String> seen = new CopyOnWriteArrayList<>();
    final ChaosEventListener failing =
        ChaosEventListener.async(
            event -> {
              seen.add(event.scenarioId());
              throw new IllegalStateException("async broke");
            });
    bus.addListener(failing);
    try (LogCapture log = LogCapture.agent()) {
      bus.publish(ChaosEvent.Type.APPLIED, "first", "applied", Map.of());
      bus.publish(ChaosEvent.Type.APPLIED, "second", "applied", Map.of());
      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                assertThat(seen).containsExactly("first", "second");
                assertThat(log.messages())
                    .filteredOn(
                        message ->
                            message.startsWith(
                                "asynchronous ChaosEventListener threw; delivery continues."))
                    .hasSize(2);
              });
    } finally {
      bus.removeListener(failing);
    }
  }

  @Test
  @DisplayName("removing an asynchronous listener whose close() throws still removes it")
  void asyncListenerCloseFailureIsIgnored() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final AtomicBoolean closeAttempted = new AtomicBoolean();
    final CloseableListener delegate =
        new CloseableListener() {
          @Override
          public void onEvent(final ChaosEvent event) {}

          @Override
          public void close() throws Exception {
            closeAttempted.set(true);
            throw new Exception("close broke");
          }
        };
    final ChaosEventListener async = ChaosEventListener.async(delegate);
    bus.addListener(async);

    assertThat(bus.removeListener(async)).isTrue();
    assertThat(closeAttempted).isTrue();
    assertThat(bus.hasListeners()).isFalse();
  }

  @Test
  @DisplayName("a listener whose close() throws is logged and the bus still closes")
  void listenerCloseFailureIsLogged() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final AtomicBoolean siblingClosed = new AtomicBoolean();
    bus.addListener(
        new CloseableListener() {
          @Override
          public void onEvent(final ChaosEvent event) {}

          @Override
          public void close() throws Exception {
            throw new Exception("close broke");
          }
        });
    bus.addListener(
        new CloseableListener() {
          @Override
          public void onEvent(final ChaosEvent event) {}

          @Override
          public void close() {
            siblingClosed.set(true);
          }
        });
    try (LogCapture log = LogCapture.agent()) {
      assertThatCode(bus::close).doesNotThrowAnyException();
      assertThat(siblingClosed).isTrue();
      assertThat(bus.hasListeners()).isFalse();
      assertThat(log.messages())
          .anyMatch(
              message ->
                  message.startsWith("ChaosEventListener threw during close; continuing. listener=")
                      && message.endsWith("error=java.lang.Exception: close broke"));
    }
  }

  @Test
  @DisplayName("a metrics sink that throws drops the metric with a warning")
  void throwingSinkIsIsolated() {
    final ObservabilityBus bus =
        new ObservabilityBus(
            new ChaosMetricsSink() {
              @Override
              public void increment(final String name, final Map<String, String> tags) {
                throw new IllegalStateException("registry gone");
              }

              @Override
              public void recordDuration(
                  final String name, final Duration duration, final Map<String, String> tags) {}
            });
    try (LogCapture log = LogCapture.agent()) {
      assertThatCode(() -> bus.incrementMetric("chaos.applied", Map.of()))
          .doesNotThrowAnyException();
      assertThat(log.messages())
          .anyMatch(
              message ->
                  message.startsWith(
                          "ChaosMetricsSink threw during incrementMetric; metric dropped."
                              + " name=chaos.applied sink=")
                      && message.endsWith("error=java.lang.IllegalStateException: registry gone"));
    }
  }

  @Test
  @DisplayName("each event type is logged at its level")
  void logsEachTypeAtItsLevel() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    try (LogCapture log = LogCapture.agent()) {
      for (final ChaosEvent.Type type : ChaosEvent.Type.values()) {
        bus.publish(type, "level-" + type, "m", Map.of());
      }
      final Map<String, Level> levels =
          log.records().stream()
              .filter(record -> record.getMessage().contains("scenarioId=\"level-"))
              .collect(
                  java.util.stream.Collectors.toMap(
                      record -> record.getMessage().substring(0, record.getMessage().indexOf(" ")),
                      LogRecord::getLevel));
      assertThat(levels)
          .containsEntry("type=STARTED", Level.INFO)
          .containsEntry("type=STOPPED", Level.INFO)
          .containsEntry("type=APPLIED", Level.FINE)
          .containsEntry("type=RELEASED", Level.FINE)
          .containsEntry("type=FAILED", Level.WARNING)
          .containsEntry("type=REGISTERED", Level.FINE)
          .containsEntry("type=SKIPPED", Level.FINER)
          .hasSize(ChaosEvent.Type.values().length);
    }
  }

  @Test
  @DisplayName(
      "removing an asynchronous listener counts the events it drops and does not interrupt the"
          + " event in progress")
  void asyncRemovalCountsDropsAndDoesNotInterrupt() throws Exception {
    // Arrange: the first event blocks in the listener, 100 more wait in its queue
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final CountDownLatch inFlight = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean interrupted = new AtomicBoolean();
    final ChaosEventListener async =
        ChaosEventListener.async(
            event -> {
              if (event.scenarioId().equals("first")) {
                inFlight.countDown();
                try {
                  release.await();
                } catch (final InterruptedException interruptedWhileBlocked) {
                  interrupted.set(true);
                }
              }
            });
    bus.addListener(async);
    bus.publish(ChaosEvent.Type.APPLIED, "first", "applied", Map.of());
    assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();
    for (int i = 0; i < 100; i++) {
      bus.publish(ChaosEvent.Type.APPLIED, "queued-" + i, "applied", Map.of());
    }

    // Act: remove it from another thread; the removal waits for the event in progress
    final Thread remover = new Thread(() -> bus.removeListener(async), "remover");
    remover.start();
    await().atMost(5, TimeUnit.SECONDS).until(() -> bus.droppedEvents() == 100L);
    release.countDown();
    remover.join(10_000L);

    // Assert
    assertThat(remover.isAlive()).isFalse();
    assertThat(interrupted).isFalse();
    assertThat(bus.droppedEvents()).isEqualTo(100L);
  }

  @Test
  @DisplayName("an asynchronous listener that removes itself from onEvent does not wait for itself")
  void asyncSelfRemovalDoesNotWait() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final AtomicReference<ChaosEventListener> self = new AtomicReference<>();
    final AtomicLong removalNanos = new AtomicLong(-1L);
    final ChaosEventListener async =
        ChaosEventListener.async(
            event -> {
              final long start = System.nanoTime();
              bus.removeListener(self.get());
              removalNanos.set(System.nanoTime() - start);
            });
    self.set(async);
    bus.addListener(async);

    bus.publish(ChaosEvent.Type.APPLIED, "once", "applied", Map.of());

    await().atMost(10, TimeUnit.SECONDS).until(() -> removalNanos.get() >= 0L);
    assertThat(Duration.ofNanos(removalNanos.get())).isLessThan(Duration.ofSeconds(1));
    assertThat(bus.hasListeners()).isFalse();
  }

  @Test
  @DisplayName("removing an asynchronous listener that never returns gives up after 5 s, once")
  void asyncRemovalOfAStuckListenerIsBounded() throws Exception {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final CountDownLatch inFlight = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final ChaosEventListener async =
        ChaosEventListener.async(
            event -> {
              inFlight.countDown();
              try {
                release.await();
              } catch (final InterruptedException interruptedWhileBlocked) {
                Thread.currentThread().interrupt();
              }
            });
    bus.addListener(async);
    bus.publish(ChaosEvent.Type.APPLIED, "stuck", "applied", Map.of());
    assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();
    try (LogCapture log = LogCapture.agent()) {
      final long start = System.nanoTime();

      assertThat(bus.removeListener(async)).isTrue();

      assertThat(Duration.ofNanos(System.nanoTime() - start))
          .isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(30));
      assertThat(log.records())
          .filteredOn(record -> record.getLevel() == Level.WARNING)
          .singleElement()
          .satisfies(record -> assertThat(record.getMessage()).contains("5000 ms"));
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("a listener that throws on every event logs one WARNING, then FINE")
  void throwingListenerWarnsOnce() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    bus.addListener(
        event -> {
          throw new IllegalStateException("listener broke");
        });
    try (LogCapture log = LogCapture.agent()) {
      for (int i = 0; i < 50; i++) {
        bus.publish(ChaosEvent.Type.APPLIED, "flood-" + i, "applied", Map.of());
      }

      final List<LogRecord> failures =
          log.records().stream()
              .filter(record -> record.getMessage().startsWith("ChaosEventListener threw"))
              .toList();
      assertThat(failures).hasSize(50);
      assertThat(failures).filteredOn(record -> record.getLevel() == Level.WARNING).hasSize(1);
      assertThat(failures).filteredOn(record -> record.getLevel() == Level.FINE).hasSize(49);
    }
  }

  @Test
  @DisplayName("an asynchronous listener that throws on every event logs one WARNING, then FINE")
  void throwingAsyncListenerWarnsOnce() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final ChaosEventListener failing =
        ChaosEventListener.async(
            event -> {
              throw new IllegalStateException("async broke");
            });
    bus.addListener(failing);
    try (LogCapture log = LogCapture.agent()) {
      for (int i = 0; i < 5; i++) {
        bus.publish(ChaosEvent.Type.APPLIED, "async-flood-" + i, "applied", Map.of());
      }

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                final List<LogRecord> failures =
                    log.records().stream()
                        .filter(
                            record ->
                                record.getMessage().startsWith("asynchronous ChaosEventListener"))
                        .toList();
                assertThat(failures).hasSize(5);
                assertThat(failures)
                    .filteredOn(record -> record.getLevel() == Level.WARNING)
                    .hasSize(1);
              });
    } finally {
      bus.removeListener(failing);
    }
  }

  @Test
  @DisplayName(
      "the log line quotes and escapes the id, the message and the attributes: one line, in"
          + " insertion order")
  void logLineIsEscaped() {
    final ObservabilityBus bus = new ObservabilityBus(ChaosMetricsSink.NOOP);
    final Map<String, String> attributes = new java.util.LinkedHashMap<>();
    attributes.put("scope", "jvm");
    attributes.put("reason", "a\rb\n\"c\"\\");
    attributes.put("control", "\u0001\u007f\u0085\u2028\u2029\t");
    try (LogCapture log = LogCapture.agent()) {
      bus.publish(ChaosEvent.Type.STARTED, "a\nb", "say \"hi\"", attributes);

      assertThat(log.messages())
          .containsExactly(
              "type=STARTED scenarioId=\"a\\nb\" message=\"say \\\"hi\\\"\""
                  + " attributes={scope=\"jvm\", reason=\"a\\rb\\n\\\"c\\\"\\\\\","
                  + " control=\"\\u0001\\u007f\\u0085\\u2028\\u2029\\u0009\"}");
      assertThat(log.messages().get(0)).doesNotContain("\n", "\r", "\u2028");
    }
  }
}
