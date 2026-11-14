package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Registry of {@link ManagedStressor} factories keyed by {@link ChaosEffect} type.
 *
 * <p>Replaces the linear {@code instanceof} chain in {@link ScenarioController} with a {@link
 * LinkedHashMap} lookup, keeping factory registration co-located and making it trivial to add new
 * stressor types without touching controller logic.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The registry map is populated once at class-load time and never mutated afterwards; reads are
 * inherently thread-safe.
 *
 * <p>Not final: a test seam (U-72 c2) overrides {@link #createIfNeeded} and hands the subclass to
 * {@link ChaosControlPlaneImpl} or {@link ScenarioController}.
 */
class StressorFactory {

  /**
   * Functional interface for a factory that constructs a {@link ManagedStressor} from a {@link
   * ChaosEffect} instance. The cast inside each lambda is safe because the map is keyed by the
   * exact effect type.
   */
  @FunctionalInterface
  interface Factory<E extends ChaosEffect> {
    ManagedStressor create(E effect, Consumer<String> onEnd);
  }

  private final Map<Class<? extends ChaosEffect>, Factory<ChaosEffect>> registry;

  /**
   * Constructs a factory registry. {@code instrumentationSupplier} is forwarded only to stressors
   * that require {@link Instrumentation} (currently {@link SafepointStormStressor}).
   *
   * @param instrumentationSupplier supplier for the JVM instrumentation handle; may return {@link
   *     Optional#empty()} when running outside an agent context
   */
  StressorFactory(final Supplier<Optional<Instrumentation>> instrumentationSupplier) {
    registry = new LinkedHashMap<>();
    register(ChaosEffect.HeapPressureEffect.class, (e, onEnd) -> new HeapPressureStressor(e));
    register(ChaosEffect.KeepAliveEffect.class, (e, onEnd) -> new KeepAliveStressor(e, onEnd));
    register(
        ChaosEffect.MetaspacePressureEffect.class, (e, onEnd) -> new MetaspacePressureStressor(e));
    register(
        ChaosEffect.DirectBufferPressureEffect.class,
        (e, onEnd) -> new DirectBufferPressureStressor(e));
    register(ChaosEffect.GcPressureEffect.class, (e, onEnd) -> new GcPressureStressor(e, onEnd));
    register(
        ChaosEffect.FinalizerBacklogEffect.class, (e, onEnd) -> new FinalizerBacklogStressor(e));
    register(ChaosEffect.DeadlockEffect.class, (e, onEnd) -> new DeadlockStressor(e, onEnd));
    register(ChaosEffect.ThreadLeakEffect.class, (e, onEnd) -> new ThreadLeakStressor(e, onEnd));
    register(ChaosEffect.ThreadLocalLeakEffect.class, (e, onEnd) -> new ThreadLocalLeakStressor(e));
    register(
        ChaosEffect.MonitorContentionEffect.class,
        (e, onEnd) -> new MonitorContentionStressor(e, onEnd));
    register(
        ChaosEffect.CodeCachePressureEffect.class,
        (e, onEnd) -> new CodeCachePressureStressor(e, onEnd));
    register(
        ChaosEffect.SafepointStormEffect.class,
        (e, onEnd) -> new SafepointStormStressor(e, instrumentationSupplier.get(), onEnd));
    register(
        ChaosEffect.StringInternPressureEffect.class,
        (e, onEnd) -> new StringInternPressureStressor(e));
    register(
        ChaosEffect.ReferenceQueueFloodEffect.class,
        (e, onEnd) -> new ReferenceQueueFloodStressor(e, onEnd));
    register(
        ChaosEffect.VirtualThreadCarrierPinningEffect.class,
        (e, onEnd) -> new VirtualThreadCarrierPinningStressor(e, onEnd));
  }

  @SuppressWarnings("unchecked") // the map is keyed by the exact effect type the factory takes
  private <E extends ChaosEffect> void register(final Class<E> type, final Factory<E> factory) {
    registry.put(type, (Factory<ChaosEffect>) factory);
  }

  /**
   * Creates a {@link ManagedStressor} for the given {@code effect}, or returns {@code null} if the
   * effect type does not require a background stressor.
   *
   * @param effect the chaos effect from the active scenario; must not be {@code null}
   * @param allowDestructiveEffects the scenario's {@code allowDestructiveEffects}; without it a
   *     carrier-pinning stressor leaves one carrier of the virtual-thread scheduler free
   * @param onEnd ends the scenario with a reason when the stressor stops producing load on its own
   *     (its thread failed, its duration or lifespan elapsed); called on a stressor thread
   * @return a started {@link ManagedStressor}, or {@code null}
   */
  @Nullable ManagedStressor createIfNeeded(
      final ChaosEffect effect,
      final boolean allowDestructiveEffects,
      final Consumer<String> onEnd) {
    ChaosEffect effective = effect;
    if (effect instanceof ChaosEffect.VirtualThreadCarrierPinningEffect pinning
        && !allowDestructiveEffects) {
      effective = VirtualThreadCarrierPinningStressor.leavingOneCarrier(pinning);
      if (effective == null) {
        return null;
      }
    }
    final Factory<ChaosEffect> factory = registry.get(effective.getClass());
    return factory != null ? factory.create(effective, onEnd) : null;
  }
}
