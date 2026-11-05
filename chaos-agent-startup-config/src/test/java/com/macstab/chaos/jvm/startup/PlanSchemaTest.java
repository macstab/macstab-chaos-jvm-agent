package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedClassResolver;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The published plan schema ({@code chaos-plan.schema.json}) is derived from the plan mapper
 * itself: the record components and creators it binds, the {@code type} names of selectors and
 * effects, and its rejection of unknown fields. This test regenerates the schema and fails when the
 * committed one differs; run it with {@code -Dchaos.schema.update=true} to rewrite the file after
 * changing the plan model. The properties the schema declares required are checked against the
 * mapper too.
 */
@DisplayName("Plan JSON schema")
class PlanSchemaTest {

  static final String RESOURCE = "com/macstab/chaos/jvm/startup/chaos-plan.schema.json";

  /** The properties a plan cannot do without; the mapper rejects their absence (checked below). */
  private static final Map<Class<?>, List<String>> REQUIRED =
      Map.of(
          ChaosPlan.class,
          List.of("scenarios"),
          ChaosPlan.Metadata.class,
          List.of("name"),
          ChaosScenario.class,
          List.of("id", "selector", "effect"),
          ActivationPolicy.RateLimit.class,
          List.of("permits", "window"));

  private final ObjectMapper mapper = ChaosPlanMapper.objectMapper();
  private final DeserializationConfig config = mapper.getDeserializationConfig();
  private final Map<String, ObjectNode> definitions = new TreeMap<>();

  @Test
  @DisplayName("the committed schema is the one the plan mapper defines")
  void committedSchemaMatchesTheMapper() throws IOException {
    final String generated = generate();
    final String update = System.getProperty("chaos.schema.update");
    if (Boolean.parseBoolean(update)) {
      final Path target = Path.of("src/main/resources").resolve(RESOURCE);
      Files.createDirectories(target.getParent());
      Files.writeString(target, generated);
      // The class path still holds the copy processResources made before the rewrite.
      return;
    }
    final String committed;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
      assertThat(in).as("%s on the class path", RESOURCE).isNotNull();
      committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(committed)
        .as(
            "plan model changed: regenerate with ./gradlew :chaos-agent-startup-config:test -PupdatePlanSchema")
        .isEqualTo(generated);
  }

  @Test
  @DisplayName("every property the schema requires is required by the mapper")
  void requiredPropertiesAreRequired() {
    final String scenario =
        "{\"id\": \"s\", \"selector\": {\"type\": \"executor\", \"operations\": [\"EXECUTOR_SUBMIT\"]},"
            + " \"effect\": {\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}}";
    assertThat(ChaosPlanMapper.read("{\"scenarios\": [" + scenario + "]}").scenarios()).hasSize(1);
    assertThatThrownBy(() -> ChaosPlanMapper.read("{\"metadata\": {\"name\": \"p\"}}"))
        .as("a plan without scenarios")
        .isInstanceOf(ConfigLoadException.class);
    for (final String field : REQUIRED.get(ChaosScenario.class)) {
      final String without =
          scenario.replaceFirst("\"" + field + "\": (\\{[^}]*\\}|\"[^\"]*\"),? ?", "");
      assertThatThrownBy(() -> ChaosPlanMapper.read("{\"scenarios\": [" + without + "]}"))
          .as("a scenario without %s", field)
          .isInstanceOf(ConfigLoadException.class);
    }
    assertThatThrownBy(
            () ->
                ChaosPlanMapper.read(
                    "{\"scenarios\": [" + scenario.replace("\"type\": \"delay\", ", "") + "]}"))
        .as("an effect without its type")
        .isInstanceOf(ConfigLoadException.class);
    assertThatThrownBy(
            () ->
                ChaosPlanMapper.read(
                    "{\"metadata\": {\"description\": \"d\"}, \"scenarios\": [" + scenario + "]}"))
        .as("metadata without a name")
        .hasMessageContaining("name");
    for (final String rateLimit : List.of("{\"window\": \"PT1S\"}", "{\"permits\": 1}")) {
      final String limited =
          scenario.replace("}}", "}, \"activationPolicy\": {\"rateLimit\": " + rateLimit + "}}");
      assertThatThrownBy(() -> ChaosPlanMapper.read("{\"scenarios\": [" + limited + "]}"))
          .as("a rate limit %s", rateLimit)
          .isInstanceOf(ConfigLoadException.class);
    }
  }

  @Test
  @DisplayName("each selector requires operations, and only the ones its schema lists")
  void selectorOperationsAreTheDeclaredOnes() {
    final String effect =
        "\"effect\": {\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}";
    final List<NamedType> selectors = List.copyOf(subtypes(ChaosSelector.class));
    int checked = 0;
    for (final NamedType selector : selectors) {
      final Set<OperationType> valid = validOperations(selector.getType());
      if (valid == null) {
        continue;
      }
      checked++;
      final String type = "\"type\": \"" + selector.getName() + "\"";
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      "{\"scenarios\": [{\"id\": \"s\", \"selector\": {"
                          + type
                          + "}, "
                          + effect
                          + "}]}"))
          .as("%s without operations", selector.getName())
          .hasMessageContaining("operations");
      final OperationType foreign =
          Arrays.stream(OperationType.values())
              .filter(operation -> !valid.contains(operation))
              .findFirst()
              .orElseThrow();
      assertThatThrownBy(
              () ->
                  ChaosPlanMapper.read(
                      "{\"scenarios\": [{\"id\": \"s\", \"selector\": {"
                          + type
                          + ", \"operations\": [\""
                          + foreign
                          + "\"]}, "
                          + effect
                          + "}]}"))
          .as("%s with %s", selector.getName(), foreign)
          .hasMessageContaining("does not support operation");
    }
    assertThat(checked).as("selectors with an operation set").isEqualTo(selectors.size() - 1);
  }

  @Test
  @DisplayName("unknown properties are rejected, as additionalProperties false declares")
  void unknownPropertiesAreRejected() {
    assertThatThrownBy(
        () ->
            ChaosPlanMapper.read(
                "{\"scenarios\": [{\"id\": \"s\", \"selectr\": {\"type\": \"executor\"}}]}"));
  }

  // ── generator ─────────────────────────────────────────────────────────────

  private String generate() throws IOException {
    final ObjectNode schema = mapper.createObjectNode();
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    schema.put("$id", "https://macstab.com/schemas/chaos-plan.schema.json");
    schema.put("title", "Chaos plan");
    schema.put(
        "description",
        "A plan of the macstab chaos agent: the scenarios to activate, each a selector, an effect and"
            + " an activation policy. Generated from the plan mapper; see docs/plan-schema.md.");
    final ObjectNode plan = schemaOf(mapper.constructType(ChaosPlan.class));
    schema.setAll(plan);
    final ObjectNode defs = schema.putObject("$defs");
    definitions.forEach(defs::set);
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema) + "\n";
  }

  private ObjectNode schemaOf(final JavaType type) {
    final Class<?> raw = type.getRawClass();
    final ObjectNode node = mapper.createObjectNode();
    if (raw == String.class || raw == char.class || raw == Character.class) {
      return node.put("type", "string");
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return node.put("type", "boolean");
    }
    if (raw == int.class
        || raw == long.class
        || raw == Integer.class
        || raw == Long.class
        || raw == short.class
        || raw == Short.class
        || raw == byte.class
        || raw == Byte.class) {
      return node.put("type", "integer");
    }
    if (raw == double.class || raw == float.class || raw == Double.class || raw == Float.class) {
      return node.put("type", "number");
    }
    if (raw == Duration.class) {
      return node.put("type", "string").put("format", "duration");
    }
    if (raw == Instant.class) {
      return node.put("type", "string").put("format", "date-time");
    }
    if (raw.isEnum()) {
      final ArrayNode values = node.put("type", "string").putArray("enum");
      for (final Object constant : raw.getEnumConstants()) {
        values.add(((Enum<?>) constant).name());
      }
      return node;
    }
    if (type.isCollectionLikeType() || type.isArrayType()) {
      node.put("type", "array");
      if (Set.class.isAssignableFrom(raw)) {
        node.put("uniqueItems", true);
      }
      node.set("items", schemaOf(type.getContentType()));
      return node;
    }
    if (type.isMapLikeType()) {
      node.put("type", "object");
      node.set("additionalProperties", schemaOf(type.getContentType()));
      return node;
    }
    return reference(type);
  }

  private ObjectNode reference(final JavaType type) {
    final Class<?> raw = type.getRawClass();
    final String name = definitionName(raw);
    if (!definitions.containsKey(name)) {
      final ObjectNode definition = mapper.createObjectNode();
      definitions.put(name, definition);
      final Collection<NamedType> subtypes = subtypes(raw);
      if (subtypes.isEmpty()) {
        describeObject(definition, type, null);
      } else {
        final ArrayNode oneOf = definition.putArray("oneOf");
        subtypes.stream()
            .sorted(Comparator.comparing(NamedType::getName))
            .forEach(
                subtype -> {
                  final String subName = definitionName(subtype.getType());
                  final ObjectNode subDefinition = mapper.createObjectNode();
                  definitions.put(subName, subDefinition);
                  describeObject(
                      subDefinition, mapper.constructType(subtype.getType()), subtype.getName());
                  oneOf.addObject().put("$ref", "#/$defs/" + subName);
                });
      }
    }
    return mapper.createObjectNode().put("$ref", "#/$defs/" + name);
  }

  private void describeObject(
      final ObjectNode definition, final JavaType type, final String discriminator) {
    definition.put("type", "object");
    final ObjectNode properties = definition.putObject("properties");
    final ArrayNode required = mapper.createArrayNode();
    if (discriminator != null) {
      properties.putObject("type").put("const", discriminator);
      required.add("type");
    }
    final BeanDescription description = config.introspect(type);
    description.findProperties().stream()
        .sorted(Comparator.comparing(BeanPropertyDefinition::getName))
        .forEach(
            property -> properties.set(property.getName(), schemaOf(property.getPrimaryType())));
    final Set<OperationType> operations = validOperations(type.getRawClass());
    if (operations != null) {
      final ObjectNode schema = properties.putObject("operations");
      schema.put("type", "array").put("minItems", 1).put("uniqueItems", true);
      final ArrayNode values = schema.putObject("items").put("type", "string").putArray("enum");
      operations.stream().sorted().forEach(operation -> values.add(operation.name()));
      required.add("operations");
    }
    for (final String name : REQUIRED.getOrDefault(type.getRawClass(), List.of())) {
      required.add(name);
    }
    if (!required.isEmpty()) {
      definition.set("required", required);
    }
    definition.put("additionalProperties", false);
  }

  /**
   * The operations a selector accepts: its {@code VALID_OPS}, the set its constructor checks {@code
   * operations} against; {@code null} for a type that has none.
   */
  @SuppressWarnings("unchecked")
  static Set<OperationType> validOperations(final Class<?> raw) {
    if (!ChaosSelector.class.isAssignableFrom(raw)) {
      return null;
    }
    try {
      final Field field = raw.getDeclaredField("VALID_OPS");
      field.setAccessible(true);
      return (Set<OperationType>) field.get(null);
    } catch (final NoSuchFieldException none) {
      return null;
    } catch (final ReflectiveOperationException unreadable) {
      throw new IllegalStateException(raw.getName() + ".VALID_OPS", unreadable);
    }
  }

  private Collection<NamedType> subtypes(final Class<?> raw) {
    if (raw != ChaosSelector.class && raw != ChaosEffect.class) {
      return List.of();
    }
    return mapper
        .getSubtypeResolver()
        .collectAndResolveSubtypesByClass(
            config, AnnotatedClassResolver.resolveWithoutSuperTypes(config, raw))
        .stream()
        .filter(named -> named.hasName() && named.getType() != raw)
        .toList();
  }

  private static String definitionName(final Class<?> raw) {
    return raw.getSimpleName();
  }
}
