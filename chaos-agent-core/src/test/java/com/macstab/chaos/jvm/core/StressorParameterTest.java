package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stressor parameters")
class StressorParameterTest {

  @Test
  @DisplayName("metaspace pressure with retain=false releases its class loader right away")
  void metaspaceRetainFalseReleasesLoader() {
    final MetaspacePressureStressor stressor =
        new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(10, 2, false));
    try {
      assertThat(stressor.retainedClassCount()).isZero();
      assertThat(stressor.holdsLoader())
          .as("the loader keeps every generated class reachable")
          .isFalse();
    } finally {
      stressor.close();
    }
  }

  @Test
  @DisplayName("metaspace pressure with retain=true keeps its classes until close")
  void metaspaceRetainTrueKeepsClasses() {
    final MetaspacePressureStressor stressor =
        new MetaspacePressureStressor(new ChaosEffect.MetaspacePressureEffect(10, 2, true));
    assertThat(stressor.retainedClassCount()).isEqualTo(10);
    assertThat(stressor.holdsLoader()).isTrue();
    stressor.close();
    assertThat(stressor.holdsLoader()).isFalse();
  }

  @Test
  @DisplayName("closing the code-cache stressor stops its compiler thread")
  void codeCacheCloseStopsCompiler() {
    final CodeCachePressureStressor stressor =
        new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(50, 20));
    final Thread compiler = stressor.compilerThread();

    stressor.close();

    assertThat(compiler).isNotNull();
    assertThat(compiler.isAlive()).as("compiler thread joined on close").isFalse();
  }
}
