package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The API's collections iterate in a defined order, the same in every JVM: event attributes and
 * runtime details in insertion order, selector operations in declaration order. Within one JVM
 * {@code Map.copyOf} and {@code Set.copyOf} also iterate the same way every time, so the tests
 * check the order itself; an accidental pass has a probability of at most 1/6!.
 */
@DisplayName("Collection order")
class CollectionOrderTest {

  /**
   * Ten keys with unrelated hashes, in an order no hash table iterates them in (keys with
   * consecutive hashes, such as {@code key-0} to {@code key-9}, come out of {@code Map.copyOf} in a
   * rotation of their order, which can match by chance).
   */
  private static Map<String, String> tenKeys() {
    final Map<String, String> attributes = new LinkedHashMap<>();
    for (final String key :
        List.of(
            "zulu",
            "operation",
            "match",
            "scope",
            "effectType",
            "delayMillis",
            "sessionId",
            "reason",
            "category",
            "alpha")) {
      attributes.put(key, key + "-value");
    }
    return attributes;
  }

  @Test
  @DisplayName("event attributes iterate in insertion order")
  void eventAttributesKeepInsertionOrder() {
    final Map<String, String> attributes = tenKeys();

    final ChaosEvent event =
        new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.APPLIED, "s", "m", attributes);

    assertThat(new ArrayList<>(event.attributes().keySet()))
        .containsExactlyElementsOf(attributes.keySet());
  }

  @Test
  @DisplayName("event attributes are an unmodifiable snapshot that refuses null keys and values")
  void eventAttributesRefuseNulls() {
    final Map<String, String> nullValue = new HashMap<>();
    nullValue.put("k", null);
    final Map<String, String> nullKey = new HashMap<>();
    nullKey.put(null, "v");
    final Map<String, String> source = new LinkedHashMap<>(Map.of("k", "v"));
    final ChaosEvent event =
        new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.APPLIED, "s", "m", source);
    source.put("later", "x");

    assertThatThrownBy(
            () -> new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.APPLIED, "s", "m", nullValue))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.APPLIED, "s", "m", nullKey))
        .isInstanceOf(NullPointerException.class);
    assertThat(event.attributes()).containsOnlyKeys("k");
    assertThatThrownBy(() -> event.attributes().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("snapshot runtime details iterate in insertion order and refuse nulls")
  void runtimeDetailsKeepInsertionOrder() {
    final Map<String, String> details = tenKeys();
    final Map<String, String> nullValue = new HashMap<>();
    nullValue.put("k", null);

    final ChaosDiagnostics.Snapshot snapshot =
        new ChaosDiagnostics.Snapshot(Instant.EPOCH, List.of(), List.of(), details);

    assertThat(new ArrayList<>(snapshot.runtimeDetails().keySet()))
        .containsExactlyElementsOf(details.keySet());
    assertThatThrownBy(
            () -> new ChaosDiagnostics.Snapshot(Instant.EPOCH, List.of(), List.of(), nullValue))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName(
      "selector operations iterate in declaration order, whatever order they were given in")
  void selectorOperationsKeepDeclarationOrder() {
    final List<OperationType> fileOperations =
        new ArrayList<>(
            EnumSet.of(
                OperationType.FILE_IO_READ,
                OperationType.FILE_IO_WRITE,
                OperationType.FILE_IO_DELETE,
                OperationType.FILE_IO_SYNC,
                OperationType.FILE_IO_TRUNCATE,
                OperationType.FILE_IO_MAP,
                OperationType.FILE_IO_OPEN,
                OperationType.FILE_IO_CLOSE));
    final List<OperationType> reversed = new ArrayList<>(fileOperations);
    Collections.reverse(reversed);

    final ChaosSelector.FileIoSelector selector =
        ChaosSelector.fileIo(new LinkedHashSet<>(reversed));

    assertThat(new ArrayList<>(selector.operations())).containsExactlyElementsOf(fileOperations);
    assertThatThrownBy(() -> selector.operations().add(OperationType.FILE_IO_MOVE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(selector.operations()).isEqualTo(Set.copyOf(fileOperations));
  }
}
