package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("API JSON mix-ins")
class ApiJsonMixinsTest {

  @Test
  @DisplayName("every selector type has a JSON type name")
  void everySelectorIsRegistered() {
    assertThat(registered("SelectorMixin"))
        .containsExactlyInAnyOrder(ChaosSelector.class.getPermittedSubclasses());
  }

  @Test
  @DisplayName("every effect type has a JSON type name")
  void everyEffectIsRegistered() {
    assertThat(registered("EffectMixin"))
        .containsExactlyInAnyOrder(ChaosEffect.class.getPermittedSubclasses());
  }

  private static List<Class<?>> registered(final String mixinName) {
    final Class<?> mixin =
        Arrays.stream(ApiJsonMixins.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals(mixinName))
            .findFirst()
            .orElseThrow();
    return Arrays.stream(mixin.getAnnotation(JsonSubTypes.class).value())
        .<Class<?>>map(JsonSubTypes.Type::value)
        .toList();
  }
}
