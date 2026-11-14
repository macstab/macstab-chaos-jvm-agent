package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;

/**
 * Child-JVM probe for {@link StressorLifecycleTest}, run on JDK 24+ with {@code
 * --sun-misc-unsafe-memory-access=deny}: builds the metaspace and code-cache stressors and prints
 * how many classes each defined ({@code metaspaceRetained=}, {@code codeCacheRetained=}) or what
 * they threw, and the state of an activated metaspace scenario ({@code state=}).
 */
final class ClassDefinitionProbeMain {

  private ClassDefinitionProbeMain() {}

  public static void main(final String[] args) {
    try {
      final MetaspacePressureStressor metaspace =
          new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(5, 1, true));
      System.out.println("metaspaceRetained=" + metaspace.retainedClassCount());
      metaspace.close();
    } catch (final RuntimeException | Error failure) {
      System.out.println("metaspaceThrown=" + failure);
    }
    final CodeCachePressureStressor codeCache =
        new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(5, 1));
    System.out.println("codeCacheRetained=" + codeCache.retainedClassCount());
    codeCache.close();
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("metaspace")
                  .selector(ChaosSelector.stress(ChaosSelector.StressTarget.METASPACE))
                  .effect(new ChaosEffect.MetaspacePressureEffect(5, 1, true))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      System.out.println("state=" + handle.state());
      handle.stop();
    } catch (final RuntimeException | Error failure) {
      System.out.println("activationThrown=" + failure);
    }
  }
}
