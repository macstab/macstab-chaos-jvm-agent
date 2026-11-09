package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationTags")
class OperationTagsTest {

  @Test
  @DisplayName("every tag is an OperationType name or a tag the core resolves")
  void everyTagResolves() throws IllegalAccessException {
    final Set<String> operations =
        Arrays.stream(OperationType.values()).map(Enum::name).collect(Collectors.toSet());
    final Set<String> transfers =
        Set.of(
            ChaosDispatcher.TRANSFER_TO,
            ChaosDispatcher.TRANSFER_FROM,
            ChaosDispatcher.SCHEDULE_SUBMIT_PERIODIC);
    int tags = 0;
    for (final Field field : OperationTags.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      final String value = (String) field.get(null);
      assertThat(value).as("value of OperationTags.%s", field.getName()).isEqualTo(field.getName());
      assertThat(operations.contains(value) || transfers.contains(value))
          .as("OperationTags.%s names an operation", field.getName())
          .isTrue();
      tags++;
    }
    assertThat(tags).isPositive();
  }
}
