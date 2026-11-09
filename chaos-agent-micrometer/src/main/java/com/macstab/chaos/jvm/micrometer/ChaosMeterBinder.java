package com.macstab.chaos.jvm.micrometer;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Publishes chaos activity as Micrometer metrics. Every meter name has one fixed set of tag keys,
 * as registries such as Prometheus require.
 *
 * <table>
 *   <caption>Meters</caption>
 *   <tr><th>Meter</th><th>Type</th><th>Tags</th><th>Measures</th></tr>
 *   <tr><td>{@code chaos.events}</td><td>counter</td><td>{@code type}, {@code scenario}</td>
 *       <td>every chaos event ({@code applied}, {@code skipped}, {@code started}, ...)</td></tr>
 *   <tr><td>{@code chaos.applications}</td><td>counter</td>
 *       <td>{@code scenario}, {@code operation}, {@code effect}</td>
 *       <td>effects that took effect, by intercepted operation and effect type</td></tr>
 *   <tr><td>{@code chaos.injected.delay}</td><td>timer</td><td>{@code scenario}, {@code operation}</td>
 *       <td>latency the agent added to calls, from the event's {@code delayNanos}</td></tr>
 *   <tr><td>{@code chaos.skips}</td><td>counter</td><td>{@code scenario}, {@code reason}</td>
 *       <td>matches the policy kept from applying (published while a listener is registered)</td></tr>
 *   <tr><td>{@code chaos.activation.failures}</td><td>counter</td>
 *       <td>{@code category}</td><td>failed activations</td></tr>
 *   <tr><td>{@code chaos.scenarios}</td><td>gauge</td><td>{@code state}</td>
 *       <td>registered scenarios per state</td></tr>
 *   <tr><td>{@code chaos.listener.events.dropped}</td><td>function counter</td><td>none</td>
 *       <td>events dropped by full asynchronous listener queues</td></tr>
 *   <tr><td>{@code chaos.hooks.woven}</td><td>gauge</td><td>none</td>
 *       <td>hook groups woven into JDK and library classes</td></tr>
 * </table>
 *
 * <p>Tag values: {@code operation} is the {@code OperationType} name in lower case ({@code
 * thread_sleep}); {@code effect} is the effect's plan JSON type ({@code delay}, {@code
 * connectionReset}), derived from the event's {@code effectType}, the effect class's simple name:
 * without its {@code Effect} suffix, first letter lower-cased.
 *
 * <p>The seven meters read from the diagnostics (the five {@code chaos.scenarios} gauges, {@code
 * chaos.listener.events.dropped} and {@code chaos.hooks.woven}) share one snapshot per scrape: a
 * snapshot is reused for one second, so a scrape takes one, and a scrape longer than that takes a
 * fresh one.
 *
 * <p>Events are counted on an agent-owned thread ({@link ChaosEventListener#async}), never on the
 * application thread that triggered them. {@link #close()} stops counting.
 *
 * <p>The series tagged with a scenario are retired once the scenario has ended ({@code STOPPED},
 * {@code INACTIVE}, or a {@code FAILED} activation) and two scrapes (reads of the {@code
 * chaos.scenarios} gauges) ran since, so its last values were exported: on the first event after
 * that, or at {@link #close()}, and only while no scenario with that id is registered or active in
 * any scope (the scopes of one id share its series). Without a later event the ended ids wait for
 * one; they are at most the scenarios that ended. For OpenTelemetry without Micrometer, use the
 * {@code chaos-agent-opentelemetry} module.
 *
 * <pre>{@code
 * new ChaosMeterBinder(ChaosPlatform.current()).bindTo(meterRegistry);
 * }</pre>
 */
public final class ChaosMeterBinder implements MeterBinder, AutoCloseable {

  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger(ChaosMeterBinder.class.getName());

  /** How long a diagnostics snapshot serves the meters: one scrape. */
  private static final long SNAPSHOT_REUSE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

  private final ChaosControlPlane controlPlane;
  private final java.util.List<ChaosEventListener> listeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();
  private final java.util.List<Series> bound = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** The last snapshot and when it was taken. */
  private volatile @Nullable TakenSnapshot lastSnapshot;

  private record TakenSnapshot(long takenAtNanos, ChaosDiagnostics.Snapshot snapshot) {}

  /**
   * Creates a binder for {@code controlPlane}.
   *
   * @param controlPlane the control plane whose activity is measured
   */
  public ChaosMeterBinder(final ChaosControlPlane controlPlane) {
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
  }

  @Override
  public void bindTo(final MeterRegistry registry) {
    final Series series = new Series(registry);
    bound.add(series);
    for (final ChaosDiagnostics.ScenarioState state : ChaosDiagnostics.ScenarioState.values()) {
      // One gauge notices the scrapes: every scrape reads it.
      final boolean scrapeProbe = state == ChaosDiagnostics.ScenarioState.REGISTERED;
      Gauge.builder(
              "chaos.scenarios",
              controlPlane,
              plane -> {
                if (scrapeProbe) {
                  series.scraped();
                }
                return countIn(snapshot(), state);
              })
          .description("Registered chaos scenarios by state")
          .tag("state", state.name().toLowerCase(Locale.ROOT))
          .register(registry);
    }
    FunctionCounter.builder(
            "chaos.listener.events.dropped",
            controlPlane,
            plane -> detail(snapshot(), "droppedEvents", Long::parseLong))
        .description("Chaos events dropped by full asynchronous listener queues")
        .register(registry);
    Gauge.builder(
            "chaos.hooks.woven",
            controlPlane,
            plane -> detail(snapshot(), "wovenHookGroups", ChaosMeterBinder::countList))
        .description("Hook groups woven into JDK and library classes")
        .register(registry);
    final ChaosEventListener counting =
        ChaosEventListener.async(
            event -> {
              final long scrapes = series.scrapes();
              series.retireEnded(scrapes, false);
              count(registry, event, series);
              series.ended(event, scrapes);
            });
    listeners.add(counting);
    controlPlane.addEventListener(counting);
  }

  /**
   * Stops counting events for every registry this binder was bound to and retires the series of
   * scenarios that ended; the other meters stay in their registry.
   */
  @Override
  public void close() {
    for (final ChaosEventListener registered : java.util.List.copyOf(listeners)) {
      controlPlane.removeEventListener(registered);
      listeners.remove(registered);
    }
    for (final Series series : java.util.List.copyOf(bound)) {
      series.retireEnded(series.scrapes(), true);
      bound.remove(series);
    }
  }

  /**
   * The diagnostics snapshot for the meters: the last one while it is younger than a second, so the
   * meters of one scrape read one snapshot.
   */
  private ChaosDiagnostics.Snapshot snapshot() {
    final long now = System.nanoTime();
    final TakenSnapshot last = lastSnapshot;
    if (last != null && now - last.takenAtNanos() < SNAPSHOT_REUSE_NANOS) {
      return last.snapshot();
    }
    final ChaosDiagnostics.Snapshot fresh = controlPlane.diagnostics().snapshot();
    lastSnapshot = new TakenSnapshot(now, fresh);
    return fresh;
  }

  private static double countIn(
      final ChaosDiagnostics.Snapshot snapshot, final ChaosDiagnostics.ScenarioState state) {
    return snapshot.scenarios().stream().filter(report -> report.state() == state).count();
  }

  private static double detail(
      final ChaosDiagnostics.Snapshot snapshot,
      final String key,
      final java.util.function.ToLongFunction<String> parse) {
    final String value = snapshot.runtimeDetails().get(key);
    if (value == null || value.isEmpty()) {
      return 0.0;
    }
    try {
      return parse.applyAsLong(value);
    } catch (final NumberFormatException unexpected) {
      return 0.0;
    }
  }

  /**
   * The effect's plan JSON type from its class's simple name: {@code DelayEffect} is {@code delay},
   * {@code ConnectionResetEffect} is {@code connectionReset}.
   */
  static String effectName(final String effectType) {
    final String base =
        effectType.endsWith("Effect")
            ? effectType.substring(0, effectType.length() - "Effect".length())
            : effectType;
    return base.isEmpty() ? effectType : Character.toLowerCase(base.charAt(0)) + base.substring(1);
  }

  private static long countList(final String commaSeparated) {
    return commaSeparated.isBlank() ? 0 : commaSeparated.split(",").length;
  }

  private static void count(
      final MeterRegistry registry, final ChaosEvent event, final Series series) {
    final String scenario = String.valueOf(event.scenarioId());
    series
        .track(
            scenario,
            Counter.builder("chaos.events")
                .description("Chaos events by type and scenario")
                .tag("type", event.type().name().toLowerCase(Locale.ROOT))
                .tag("scenario", scenario)
                .register(registry))
        .increment();
    final java.util.Map<String, String> attributes = event.attributes();
    switch (event.type()) {
      case APPLIED -> {
        final String operation =
            attributes.getOrDefault("operation", "unknown").toLowerCase(Locale.ROOT);
        series
            .track(
                scenario,
                Counter.builder("chaos.applications")
                    .description("Chaos effects that took effect")
                    .tag("scenario", scenario)
                    .tag("operation", operation)
                    .tag("effect", effectName(attributes.getOrDefault("effectType", "unknown")))
                    .register(registry))
            .increment();
        final String delay = attributes.get("delayNanos");
        if (delay != null) {
          series
              .track(
                  scenario,
                  Timer.builder("chaos.injected.delay")
                      .description("Latency the chaos agent added to calls")
                      .tag("scenario", scenario)
                      .tag("operation", operation)
                      .register(registry))
              .record(Duration.ofNanos(Long.parseLong(delay)));
        }
      }
      case SKIPPED ->
          series
              .track(
                  scenario,
                  Counter.builder("chaos.skips")
                      .description("Matches the activation policy kept from applying")
                      .tag("scenario", scenario)
                      .tag("reason", attributes.getOrDefault("reason", "unknown"))
                      .register(registry))
              .increment();
      case FAILED ->
          Counter.builder("chaos.activation.failures")
              .description("Failed scenario activations")
              .tag("category", attributes.getOrDefault("category", "unknown"))
              .register(registry)
              .increment();
      default -> {}
    }
  }

  /**
   * The scenario-tagged meters of one registry, and the ended scenarios whose meters are retired
   * once their last values were scraped. Meters are remembered as {@code register} returned them,
   * so they are removed by the id the registry mapped them to ({@code MeterFilter}s included).
   */
  private final class Series {
    private final MeterRegistry registry;

    /** Reads of the scrape probe gauge so far. */
    private final java.util.concurrent.atomic.AtomicLong scrapes =
        new java.util.concurrent.atomic.AtomicLong();

    /** Guarded by {@code this}: the meters per scenario id. */
    private final java.util.Map<String, java.util.Set<Meter>> meters = new java.util.HashMap<>();

    /**
     * Guarded by {@code this}: the scenario ids that ended and were not retired yet, with the
     * scrape count the end event read before it was counted.
     */
    private final java.util.Map<String, Long> endedAt = new java.util.LinkedHashMap<>();

    /** Whether a failed removal was logged: it is logged once. */
    private boolean removalFailureLogged;

    Series(final MeterRegistry registry) {
      this.registry = registry;
    }

    void scraped() {
      scrapes.incrementAndGet();
    }

    long scrapes() {
      return scrapes.get();
    }

    synchronized <M extends Meter> M track(final String scenarioId, final M meter) {
      meters.computeIfAbsent(scenarioId, id -> new java.util.HashSet<>()).add(meter);
      return meter;
    }

    /**
     * Marks the scenario of an end event ({@code STOPPED}, {@code INACTIVE}, {@code FAILED}).
     *
     * @param scrapes the scrape count the event read before it was counted
     */
    synchronized void ended(final ChaosEvent event, final long scrapes) {
      switch (event.type()) {
        case STOPPED, INACTIVE, FAILED -> endedAt.put(event.scenarioId(), scrapes);
        default -> {}
      }
    }

    /**
     * Retires the series of the ended scenarios a whole scrape ran for after they were counted (two
     * scrapes since the end event read the count: one may have overlapped the counting), or of all
     * of them ({@code all}), unless a scenario of that id is registered or active again in some
     * scope.
     *
     * @param scrapes the scrape count now
     * @param all whether every ended id is due, scraped or not
     */
    synchronized void retireEnded(final long scrapes, final boolean all) {
      if (endedAt.isEmpty()
          || (!all && endedAt.values().stream().noneMatch(at -> at + 2 <= scrapes))) {
        return;
      }
      final java.util.Set<String> live = new java.util.HashSet<>();
      for (final ChaosDiagnostics.ScenarioReport report :
          controlPlane.diagnostics().snapshot().scenarios()) {
        if (report.state() == ChaosDiagnostics.ScenarioState.REGISTERED
            || report.state() == ChaosDiagnostics.ScenarioState.ACTIVE) {
          live.add(report.id());
        }
      }
      final java.util.Iterator<java.util.Map.Entry<String, Long>> ended =
          endedAt.entrySet().iterator();
      while (ended.hasNext()) {
        final java.util.Map.Entry<String, Long> entry = ended.next();
        if (live.contains(entry.getKey())) {
          // The id runs again in some scope: its series goes on; its next end marks it again.
          ended.remove();
        } else if (all || entry.getValue() + 2 <= scrapes) {
          ended.remove();
          // Never null: the event that marked the id registered its chaos.events series first.
          remove(Objects.requireNonNullElse(meters.remove(entry.getKey()), java.util.Set.of()));
        }
      }
    }

    private void remove(final java.util.Set<Meter> retired) {
      for (final Meter meter : retired) {
        try {
          registry.remove(meter);
        } catch (final RuntimeException failure) {
          if (!removalFailureLogged) {
            removalFailureLogged = true;
            LOGGER.log(
                java.util.logging.Level.WARNING,
                failure,
                () -> "[chaos-agent] could not remove the meter " + meter.getId());
          }
        }
      }
    }
  }
}
