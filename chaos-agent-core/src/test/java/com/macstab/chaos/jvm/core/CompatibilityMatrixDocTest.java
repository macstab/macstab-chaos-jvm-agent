package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The selector and effect compatibility table of {@code docs/configuration-reference.md} §13.4 is
 * generated from {@link CompatibilityValidator}: for each interceptor effect and each operation, a
 * scenario with a selector of that operation is validated, and the table lists the operations that
 * pass. The test fails when the committed table differs; {@code -PupdateDocs} rewrites it.
 */
@DisplayName("The compatibility table is the validator's")
class CompatibilityMatrixDocTest {

  private static final String BEGIN = "<!-- compatibility-table:begin (generated) -->";
  private static final String END = "<!-- compatibility-table:end -->";
  private static final FeatureSet FEATURES = new FeatureSet();

  /** One instance of every interceptor effect, in the order of the effect reference. */
  private static final Map<String, List<ChaosEffect>> EFFECTS = new LinkedHashMap<>();

  static {
    EFFECTS.put("delay", List.of(ChaosEffect.delay(Duration.ofMillis(1))));
    EFFECTS.put("gate", List.of(ChaosEffect.gate(Duration.ofMillis(1))));
    EFFECTS.put("reject", List.of(ChaosEffect.reject("chaos")));
    EFFECTS.put(
        "errno",
        Arrays.stream(ChaosEffect.Errno.values())
            .map(errno -> (ChaosEffect) ChaosEffect.errno(errno))
            .toList());
    EFFECTS.put("trickle", List.of(ChaosEffect.trickle(16)));
    EFFECTS.put("corruptRead", List.of(ChaosEffect.corruptRead()));
    EFFECTS.put("timeout", List.of(ChaosEffect.timeout()));
    EFFECTS.put("connectionReset", List.of(ChaosEffect.connectionReset()));
    EFFECTS.put("tornWrite", List.of(ChaosEffect.tornWrite()));
    EFFECTS.put("dnsAnswer", List.of(ChaosEffect.dnsShuffle()));
    EFFECTS.put("dnsRewrite", List.of(ChaosEffect.dnsRewrite("other.internal")));
    EFFECTS.put("redirect", List.of(ChaosEffect.redirect("other.internal")));
    EFFECTS.put("suppress", List.of(ChaosEffect.suppress()));
    EFFECTS.put(
        "exceptionalCompletion",
        List.of(ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.TIMEOUT, "chaos")));
    EFFECTS.put(
        "exceptionInjection", List.of(ChaosEffect.injectException("java.io.IOException", "chaos")));
    EFFECTS.put(
        "returnValueCorruption",
        List.of(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL)));
    EFFECTS.put(
        "clockSkew",
        List.of(ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED)));
    EFFECTS.put("spuriousWakeup", List.of(ChaosEffect.spuriousWakeup()));
  }

  @Test
  @DisplayName("the committed table is the one the validator defines")
  void tableMatchesValidator() throws IOException {
    final Path reference = root().resolve("docs/configuration-reference.md");
    final String document = Files.readString(reference);
    final int begin = document.indexOf(BEGIN);
    final int end = document.indexOf(END);
    assertThat(begin).as("table markers in %s", reference).isNotNegative().isLessThan(end);
    final String generated = BEGIN + "\n" + table() + END;
    if (Boolean.getBoolean("chaos.docs.update")) {
      Files.writeString(
          reference,
          document.substring(0, begin) + generated + document.substring(end + END.length()));
      return;
    }
    assertThat(document.substring(begin, end + END.length()))
        .as("compatibility table changed: regenerate with -PupdateDocs")
        .isEqualTo(generated);
  }

  @Test
  @DisplayName("every interceptor effect type has a row")
  void everyInterceptorEffectHasARow() {
    final Set<Class<?>> covered =
        EFFECTS.values().stream()
            .flatMap(List::stream)
            .map(Object::getClass)
            .collect(Collectors.toSet());
    for (final Class<?> type : ChaosEffect.class.getPermittedSubclasses()) {
      if (!isStressor(type)) {
        assertThat(covered).as("a row for %s", type.getSimpleName()).contains(type);
      }
    }
  }

  private static String table() {
    final Map<OperationType, List<ChaosSelector>> selectors = selectorsByOperation();
    assertThat(selectors.keySet())
        .as("every operation has a selector to probe it with")
        .isEqualTo(EnumSet.complementOf(EnumSet.of(OperationType.LIFECYCLE)));
    final StringBuilder table = new StringBuilder();
    table.append("| Effect | Operations it applies to |\n");
    table.append("|--------|--------------------------|\n");
    for (final Map.Entry<String, List<ChaosEffect>> effect : EFFECTS.entrySet()) {
      final List<OperationType> applies = new ArrayList<>();
      final List<OperationType> not = new ArrayList<>();
      for (final OperationType operation : selectors.keySet()) {
        (compatible(effect.getValue(), selectors.get(operation)) ? applies : not).add(operation);
      }
      table
          .append("| `")
          .append(effect.getKey())
          .append("` | ")
          .append(
              applies.size() > not.size()
                  ? "every operation except " + names(not)
                  : applies.isEmpty() ? "none" : names(applies))
          .append(" |\n");
    }
    return table.toString();
  }

  private static String names(final List<OperationType> operations) {
    return operations.stream()
        .map(operation -> "`" + operation.name() + "`")
        .collect(Collectors.joining(", "));
  }

  private static boolean compatible(
      final List<ChaosEffect> effects, final List<ChaosSelector> selectors) {
    for (final ChaosEffect effect : effects) {
      for (final ChaosSelector selector : selectors) {
        try {
          CompatibilityValidator.validate(
              ChaosScenario.builder("probe")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(selector)
                  .effect(effect)
                  .activationPolicy(ActivationPolicy.always())
                  .build(),
              FEATURES);
          return true;
        } catch (final ChaosValidationException | IllegalArgumentException incompatible) {
          // try the next selector or effect instance
        }
      }
    }
    return false;
  }

  /** For each operation, the selectors (with no filter) that select only that operation. */
  private static Map<OperationType, List<ChaosSelector>> selectorsByOperation() {
    final Map<OperationType, List<ChaosSelector>> selectors =
        new java.util.EnumMap<>(OperationType.class);
    for (final Class<?> type : ChaosSelector.class.getPermittedSubclasses()) {
      for (final OperationType operation : OperationType.values()) {
        ChaosSelector selector = unfiltered(type, operation, NamePattern.any());
        if (selector == null) {
          // A method selector needs one pattern that is not ANY.
          selector = unfiltered(type, operation, NamePattern.exact("probe"));
        }
        if (selector != null) {
          selectors.computeIfAbsent(operation, any -> new ArrayList<>()).add(selector);
        }
      }
    }
    return selectors;
  }

  private static ChaosSelector unfiltered(
      final Class<?> type, final OperationType operation, final NamePattern pattern) {
    final RecordComponent[] components = type.getRecordComponents();
    if (components == null
        || Arrays.stream(components).noneMatch(c -> c.getName().equals("operations"))) {
      return null;
    }
    final Object[] arguments = new Object[components.length];
    final Class<?>[] parameterTypes = new Class<?>[components.length];
    for (int index = 0; index < components.length; index++) {
      parameterTypes[index] = components[index].getType();
      arguments[index] =
          components[index].getName().equals("operations")
              ? EnumSet.of(operation)
              : parameterTypes[index] == NamePattern.class
                  ? pattern
                  : defaultValue(parameterTypes[index]);
    }
    try {
      final Constructor<?> canonical = type.getDeclaredConstructor(parameterTypes);
      canonical.setAccessible(true);
      return (ChaosSelector) canonical.newInstance(arguments);
    } catch (final InvocationTargetException rejected) {
      return null;
    } catch (final ReflectiveOperationException unreachable) {
      throw new IllegalStateException(type.getName(), unreachable);
    }
  }

  private static Object defaultValue(final Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  /** A stressor is an effect {@link StressorFactory} builds a stressor for. */
  private static boolean isStressor(final Class<?> type) {
    try {
      final java.lang.reflect.Field registry = StressorFactory.class.getDeclaredField("registry");
      registry.setAccessible(true);
      return ((Map<?, ?>) registry.get(new StressorFactory(java.util.Optional::empty)))
          .containsKey(type);
    } catch (final ReflectiveOperationException unreadable) {
      throw new IllegalStateException(unreadable);
    }
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isRegularFile(root.resolve("docs/configuration-reference.md"))) {
      root = root.getParent();
    }
    return root;
  }
}
