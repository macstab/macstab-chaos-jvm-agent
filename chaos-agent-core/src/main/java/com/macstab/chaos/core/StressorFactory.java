package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Registry of {@link ManagedStressor} factories keyed by {@link ChaosEffect} type.
 *
 * <p>Replaces the linear {@code instanceof} chain in {@link ScenarioController} with a
 * {@link LinkedHashMap} lookup, keeping factory registration co-located and making it trivial to
 * add new stressor types without touching controller logic.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The registry map is populated once at class-load time and never mutated afterwards; reads are
 * inherently thread-safe.
 */
final class StressorFactory {

  /**
   * Functional interface for a factory that constructs a {@link ManagedStressor} from a
   * {@link ChaosEffect} instance.  The cast inside each lambda is safe because the map is keyed
   * by the exact effect type.
   */
  @FunctionalInterface
  interface Factory<E extends ChaosEffect> {
    ManagedStressor create(E effect);
  }

  private final Map<Class<? extends ChaosEffect>, Factory<ChaosEffect>> registry;

  /**
   * Constructs a factory registry.  {@code instrumentationSupplier} is forwarded only to stressors
   * that require {@link Instrumentation} (currently {@link SafepointStormStressor}).
   *
   * @param instrumentationSupplier supplier for the JVM instrumentation handle; may return
   *                                 {@link Optional#empty()} when running outside an agent context
   */
  @SuppressWarnings("unchecked")
  StressorFactory(final Supplier<Optional<Instrumentation>> instrumentationSupplier) {
    registry = new LinkedHashMap<>();
    register(ChaosEffect.HeapPressureEffect.class,        e -> new HeapPressureStressor(e));
    register(ChaosEffect.KeepAliveEffect.class,           e -> new KeepAliveStressor(e));
    register(ChaosEffect.MetaspacePressureEffect.class,   e -> new MetaspacePressureStressor(e));
    register(ChaosEffect.DirectBufferPressureEffect.class,e -> new DirectBufferPressureStressor(e));
    register(ChaosEffect.GcPressureEffect.class,          e -> new GcPressureStressor(e));
    register(ChaosEffect.FinalizerBacklogEffect.class,    e -> new FinalizerBacklogStressor(e));
    register(ChaosEffect.DeadlockEffect.class,            e -> new DeadlockStressor(e));
    register(ChaosEffect.ThreadLeakEffect.class,          e -> new ThreadLeakStressor(e));
    register(ChaosEffect.ThreadLocalLeakEffect.class,     e -> new ThreadLocalLeakStressor(e));
    register(ChaosEffect.MonitorContentionEffect.class,   e -> new MonitorContentionStressor(e));
    register(ChaosEffect.CodeCachePressureEffect.class,   e -> new CodeCachePressureStressor(e));
    register(ChaosEffect.SafepointStormEffect.class,
        e -> new SafepointStormStressor(e, instrumentationSupplier.get()));
    register(ChaosEffect.StringInternPressureEffect.class,e -> new StringInternPressureStressor(e));
    register(ChaosEffect.ReferenceQueueFloodEffect.class, e -> new ReferenceQueueFloodStressor(e));
  }

  @SuppressWarnings("unchecked")
  private <E extends ChaosEffect> void register(
      final Class<E> type, final Factory<E> factory) {
    registry.put(type, (Factory<ChaosEffect>) factory);
  }

  /**
   * Creates a {@link ManagedStressor} for the given {@code effect}, or returns {@code null} if the
   * effect type does not require a background stressor.
   *
   * @param effect the chaos effect from the active scenario; must not be {@code null}
   * @return a started {@link ManagedStressor}, or {@code null}
   */
  ManagedStressor createIfNeeded(final ChaosEffect effect) {
    final Factory<ChaosEffect> factory = registry.get(effect.getClass());
    return factory != null ? factory.create(effect) : null;
  }
}
