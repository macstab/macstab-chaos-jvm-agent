package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
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
import java.util.function.Consumer;
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

  private static final String EXACT = "{'mode': 'EXACT', 'value': 'x'}";

  private static final String SCENARIO =
      "{'id': 's', 'selector': {'type': 'executor', 'operations': ['EXECUTOR_SUBMIT']},"
          + " 'effect': {'type': 'suppress'}}";

  /**
   * Valid JSON for every type of the plan model, in each shape that needs a different set of
   * properties (single quotes stand for double quotes). The schema's {@code required}, {@code
   * minItems} and non-blank constraints are probed against the mapper with these samples: a
   * constraint is declared only if the mapper rejects its violation in <em>every</em> sample, so
   * the schema is never stricter than the agent. A new type needs a sample here (the generator
   * names it); a new rule that makes a property required only in some shapes needs a sample of each
   * shape.
   */
  private static final Map<Class<?>, List<String>> SAMPLES =
      Map.ofEntries(
          samples(
              ChaosPlan.class,
              "{'scenarios': [" + SCENARIO + "]}",
              "{'metadata': {'name': 'p', 'description': 'd'}, 'observability': {'jmxEnabled':"
                  + " true, 'structuredLoggingEnabled': true, 'debugDumpOnStart': false},"
                  + " 'scenarios': ["
                  + SCENARIO
                  + "]}"),
          samples(ChaosPlan.Metadata.class, "{'name': 'p', 'description': 'd'}", "{'name': 'p'}"),
          samples(
              ChaosPlan.Observability.class,
              "{'jmxEnabled': true, 'structuredLoggingEnabled': false, 'debugDumpOnStart': true}",
              "{}"),
          samples(
              ChaosScenario.class,
              "{'id': 's', 'description': 'd', 'scope': 'SESSION', 'precedence': 1,"
                  + " 'tags': {'k': 'v'}, 'activationPolicy': {'probability': 0.5},"
                  + " 'selector': {'type': 'executor', 'operations': ['EXECUTOR_SUBMIT']},"
                  + " 'effect': {'type': 'suppress'}}"),
          samples(
              ActivationPolicy.class,
              "{'probability': 0.5, 'activateAfterMatches': 1, 'maxApplications': 2, 'activeFor':"
                  + " 'PT1M', 'activeFrom': '2026-01-01T00:00:00Z', 'activeUntil':"
                  + " '2027-01-01T00:00:00Z', 'rateLimit': {'permits': 1, 'window': 'PT1S'},"
                  + " 'randomSeed': 7, 'allowDestructiveEffects': true, 'startMode': 'MANUAL'}",
              "{'replayMatches': [1, 2]}",
              "{}"),
          samples(ActivationPolicy.RateLimit.class, "{'permits': 1, 'window': 'PT1S'}"),
          samples(
              NamePattern.class,
              "{'mode': 'EXACT', 'value': 'x'}",
              "{'mode': 'REGEX', 'value': 'x.*'}",
              "{'mode': 'ANY'}",
              "{'mode': 'ANY', 'value': '*'}",
              "{}"),
          samples(PathPattern.class, "{'prefix': '/tmp'}"),
          // Selectors.
          samples(
              ChaosSelector.AsyncSelector.class,
              "{'type': 'async', 'operations': ['ASYNC_CANCEL']}"),
          samples(
              ChaosSelector.AwsSdkSelector.class,
              "{'type': 'awsSdk', 'operations': ['AWS_SDK_CALL'], 'servicePattern': "
                  + EXACT
                  + ","
                  + " 'operationPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.ClassLoadingSelector.class,
              "{'type': 'classLoading', 'operations': ['CLASS_LOAD'], 'targetNamePattern': "
                  + EXACT
                  + ", 'loaderClassPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.DnsSelector.class,
              "{'type': 'dns', 'operations': ['DNS_RESOLVE'], 'hostnamePattern': " + EXACT + "}"),
          samples(
              ChaosSelector.ExecutorSelector.class,
              "{'type': 'executor', 'operations': ['EXECUTOR_SUBMIT'], 'executorClassPattern': "
                  + EXACT
                  + ", 'taskClassPattern': "
                  + EXACT
                  + ", 'scheduledOnly': true}"),
          samples(
              ChaosSelector.FileIoSelector.class,
              "{'type': 'fileIo', 'operations': ['FILE_IO_READ'], 'pathPattern': {'prefix':"
                  + " '/tmp'}, 'minBytes': 1, 'maxBytes': 2}"),
          samples(
              ChaosSelector.HttpClientSelector.class,
              "{'type': 'httpClient', 'operations': ['HTTP_CLIENT_SEND'], 'urlPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.JdbcSelector.class,
              "{'type': 'jdbc', 'operations': ['JDBC_STATEMENT_EXECUTE'], 'targetPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.JvmRuntimeSelector.class,
              "{'type': 'jvmRuntime', 'operations': ['INSTANT_NOW']}"),
          samples(
              ChaosSelector.MessagingSelector.class,
              "{'type': 'messaging', 'operations': ['MESSAGE_SEND'], 'destinationPattern': "
                  + EXACT
                  + "}"),
          // A method selector needs one pattern other than ANY: either one.
          samples(
              ChaosSelector.MethodSelector.class,
              "{'type': 'method', 'operations': ['METHOD_ENTER'], 'classPattern': "
                  + EXACT
                  + ", 'signaturePattern': "
                  + EXACT
                  + "}",
              "{'type': 'method', 'operations': ['METHOD_ENTER'], 'methodNamePattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.MonitorSelector.class,
              "{'type': 'monitor', 'operations': ['MONITOR_ENTER'], 'monitorClassPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.NettySelector.class,
              "{'type': 'netty', 'operations': ['NETTY_CHANNEL_WRITE'], 'remoteCidr':"
                  + " '10.0.0.0/8', 'remotePort': 80}"),
          samples(
              ChaosSelector.NetworkSelector.class,
              "{'type': 'network', 'operations': ['SOCKET_READ'], 'remoteHostPattern': "
                  + EXACT
                  + ", 'remoteCidr': '10.0.0.0/8', 'remotePort': 80, 'minBytes': 1, 'maxBytes':"
                  + " 2}"),
          samples(
              ChaosSelector.NioSelector.class,
              "{'type': 'nio', 'operations': ['NIO_CHANNEL_READ'], 'channelClassPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.ProcessSelector.class,
              "{'type': 'process', 'operations': ['PROCESS_START'], 'commandPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.QueueSelector.class,
              "{'type': 'queue', 'operations': ['QUEUE_PUT'], 'queueClassPattern': " + EXACT + "}"),
          samples(
              ChaosSelector.RedisSelector.class,
              "{'type': 'redis', 'operations': ['REDIS_COMMAND'], 'commandPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.RpcSelector.class,
              "{'type': 'rpc', 'operations': ['RPC_CALL'], 'methodPattern': " + EXACT + "}"),
          samples(
              ChaosSelector.SchedulingSelector.class,
              "{'type': 'scheduling', 'operations': ['SCHEDULE_SUBMIT'], 'executorClassPattern': "
                  + EXACT
                  + ", 'periodicOnly': true}"),
          samples(
              ChaosSelector.ShutdownSelector.class,
              "{'type': 'shutdown', 'operations': ['EXECUTOR_SHUTDOWN'], 'targetClassPattern': "
                  + EXACT
                  + "}"),
          samples(
              ChaosSelector.SslSelector.class, "{'type': 'ssl', 'operations': ['SSL_HANDSHAKE']}"),
          samples(ChaosSelector.StressSelector.class, "{'type': 'stress', 'target': 'HEAP'}"),
          samples(
              ChaosSelector.ThreadSelector.class,
              "{'type': 'thread', 'operations': ['THREAD_START'], 'kind': 'PLATFORM',"
                  + " 'threadNamePattern': "
                  + EXACT
                  + ", 'daemon': true}"),
          samples(
              ChaosSelector.ThreadLocalSelector.class,
              "{'type': 'threadLocal', 'operations': ['THREAD_LOCAL_GET'],"
                  + " 'threadLocalClassPattern': "
                  + EXACT
                  + "}"),
          // Effects.
          samples(
              ChaosEffect.ClockSkewEffect.class,
              "{'type': 'clockSkew', 'skewAmount': 'PT1S', 'mode': 'FIXED'}"),
          samples(
              ChaosEffect.CodeCachePressureEffect.class,
              "{'type': 'codeCachePressure', 'classCount': 1, 'methodsPerClass': 1}"),
          samples(ChaosEffect.ConnectionResetEffect.class, "{'type': 'connectionReset'}"),
          samples(ChaosEffect.CorruptReadEffect.class, "{'type': 'corruptRead'}"),
          samples(
              ChaosEffect.DeadlockEffect.class,
              "{'type': 'deadlock', 'participantCount': 2, 'acquisitionDelay': 'PT0.01S'}"),
          samples(
              ChaosEffect.DelayEffect.class,
              "{'type': 'delay', 'minDelay': 'PT0.001S', 'maxDelay': 'PT0.002S'}"),
          samples(
              ChaosEffect.DirectBufferPressureEffect.class,
              "{'type': 'directBufferPressure', 'totalBytes': 2048, 'bufferSizeBytes': 1024,"
                  + " 'registerCleaner': true}"),
          // A DNS answer needs one of its four settings: any one.
          samples(
              ChaosEffect.DnsAnswerEffect.class,
              "{'type': 'dnsAnswer', 'override': ['10.0.0.1'], 'family': 'IPV4', 'shuffle': true,"
                  + " 'limit': 1}",
              "{'type': 'dnsAnswer', 'override': ['10.0.0.1']}",
              "{'type': 'dnsAnswer', 'family': 'IPV6'}",
              "{'type': 'dnsAnswer', 'shuffle': true}",
              "{'type': 'dnsAnswer', 'limit': 1}"),
          samples(ChaosEffect.DnsRewriteEffect.class, "{'type': 'dnsRewrite', 'host': 'h'}"),
          samples(ChaosEffect.ErrnoEffect.class, "{'type': 'errno', 'errno': 'ECONNREFUSED'}"),
          samples(
              ChaosEffect.ExceptionInjectionEffect.class,
              "{'type': 'exceptionInjection', 'exceptionClassName':"
                  + " 'java.lang.IllegalStateException', 'message': 'm', 'withStackTrace': false}"),
          samples(
              ChaosEffect.ExceptionalCompletionEffect.class,
              "{'type': 'exceptionalCompletion', 'failureKind': 'IO', 'message': 'm'}"),
          samples(
              ChaosEffect.FinalizerBacklogEffect.class,
              "{'type': 'finalizerBacklog', 'objectCount': 1, 'finalizerDelay': 'PT0.01S'}"),
          samples(ChaosEffect.GateEffect.class, "{'type': 'gate', 'maxBlock': 'PT1S'}"),
          samples(
              ChaosEffect.GcPressureEffect.class,
              "{'type': 'gcPressure', 'allocationRateBytesPerSecond': 1024, 'objectSizeBytes': 64,"
                  + " 'promoteToOldGen': true, 'duration': 'PT1S'}"),
          samples(
              ChaosEffect.HeapPressureEffect.class,
              "{'type': 'heapPressure', 'bytes': 1024, 'chunkSizeBytes': 512}"),
          samples(
              ChaosEffect.KeepAliveEffect.class,
              "{'type': 'keepAlive', 'threadName': 't', 'daemon': true, 'heartbeat': 'PT1S'}"),
          samples(
              ChaosEffect.MetaspacePressureEffect.class,
              "{'type': 'metaspacePressure', 'generatedClassCount': 1, 'fieldsPerClass': 1,"
                  + " 'retain': false}"),
          samples(
              ChaosEffect.MonitorContentionEffect.class,
              "{'type': 'monitorContention', 'lockHoldDuration': 'PT0.01S',"
                  + " 'contendingThreadCount': 2, 'unfair': true}"),
          samples(
              ChaosEffect.RedirectEffect.class, "{'type': 'redirect', 'host': 'h', 'port': 8080}"),
          samples(
              ChaosEffect.ReferenceQueueFloodEffect.class,
              "{'type': 'referenceQueueFlood', 'referenceCount': 1, 'floodInterval': 'PT1S'}"),
          samples(ChaosEffect.RejectEffect.class, "{'type': 'reject', 'message': 'm'}"),
          samples(
              ChaosEffect.ReturnValueCorruptionEffect.class,
              "{'type': 'returnValueCorruption', 'strategy': 'NULL'}"),
          samples(
              ChaosEffect.SafepointStormEffect.class,
              "{'type': 'safepointStorm', 'gcInterval': 'PT1S', 'retransformClassCount': 1}"),
          samples(ChaosEffect.SpuriousWakeupEffect.class, "{'type': 'spuriousWakeup'}"),
          samples(
              ChaosEffect.StringInternPressureEffect.class,
              "{'type': 'stringInternPressure', 'internCount': 1, 'stringLengthBytes': 1}"),
          samples(ChaosEffect.SuppressEffect.class, "{'type': 'suppress'}"),
          samples(
              ChaosEffect.ThreadLeakEffect.class,
              "{'type': 'threadLeak', 'threadCount': 1, 'namePrefix': 'leak-', 'daemon': true,"
                  + " 'lifespan': 'PT1S'}"),
          samples(
              ChaosEffect.ThreadLocalLeakEffect.class,
              "{'type': 'threadLocalLeak', 'entriesPerThread': 1, 'valueSizeBytes': 1}"),
          samples(ChaosEffect.TimeoutEffect.class, "{'type': 'timeout'}"),
          samples(ChaosEffect.TornWriteEffect.class, "{'type': 'tornWrite'}"),
          samples(ChaosEffect.TrickleEffect.class, "{'type': 'trickle', 'maxBytes': 1}"),
          samples(
              ChaosEffect.VirtualThreadCarrierPinningEffect.class,
              "{'type': 'virtualThreadCarrierPinning', 'pinnedThreadCount': 1, 'pinDuration':"
                  + " 'PT0.01S'}"));

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
  @DisplayName(
      "the published schema requires, and keeps non-empty and non-blank, what the mapper does")
  void derivedConstraintsAreTheMappersRules() throws IOException {
    // While the schema is rewritten, the class path still holds the previous copy.
    assumeFalse(Boolean.getBoolean("chaos.schema.update"));
    final JsonNode defs;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
      assertThat(in).as("%s on the class path", RESOURCE).isNotNull();
      defs = mapper.readTree(in).get("$defs");
    }
    assertThat(defs.at("/RejectEffect/required"))
        .as("a reject without a message is refused")
        .extracting(JsonNode::asText)
        .contains("message");
    assertThat(defs.at("/ChaosPlan/required"))
        .extracting(JsonNode::asText)
        .containsExactly("scenarios");
    assertThat(defs.at("/ChaosPlan/properties/scenarios/minItems").asInt())
        .as("a plan without scenarios is refused")
        .isEqualTo(1);
    assertThat(defs.at("/Metadata/properties/name/pattern").asText()).isEqualTo("\\S");
    assertThat(defs.at("/ChaosScenario/required"))
        .extracting(JsonNode::asText)
        .containsExactly("effect", "id", "selector");
    assertThat(defs.at("/RateLimit/required"))
        .extracting(JsonNode::asText)
        .containsExactly("permits", "window");
    // {} and {"mode": "ANY"} are valid patterns: nothing is required, and ANY takes a blank value.
    assertThat(defs.at("/NamePattern/required").isMissingNode()).isTrue();
    assertThat(defs.at("/NamePattern/properties/value/pattern").isMissingNode()).isTrue();
    // Any one of the four settings makes a DNS answer: none of them is required on its own.
    assertThat(defs.at("/DnsAnswerEffect/required"))
        .extracting(JsonNode::asText)
        .containsExactly("type");
    assertThat(defs.at("/DnsAnswerEffect/properties/override/minItems").asInt()).isEqualTo(1);
    assertThat(defs.at("/ExecutorSelector/required"))
        .extracting(JsonNode::asText)
        .containsExactly("type", "operations");
  }

  @Test
  @DisplayName("a type of the plan model without a sample is named")
  void aTypeWithoutASampleIsNamed() {
    assertThatThrownBy(() -> variants(Duration.class))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("add a sample for Duration");
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
    final String valid = json("{'scenarios': [" + SCENARIO + "]}");
    assertThat(ChaosPlanMapper.read(valid).scenarios()).hasSize(1);
    assertThatThrownBy(() -> ChaosPlanMapper.read(valid.replace("\"selector\"", "\"selectr\"")))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("unknown property 'selectr'");
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
    final Class<?> raw = type.getRawClass();
    final List<ObjectNode> variants = variants(raw);
    final BeanDescription description = config.introspect(type);
    final Set<OperationType> operations = validOperations(raw);
    description.findProperties().stream()
        .sorted(Comparator.comparing(BeanPropertyDefinition::getName))
        .forEach(
            property -> {
              final String name = property.getName();
              if (discriminator != null && name.equals("type")) {
                return;
              }
              final ObjectNode schema = schemaOf(property.getPrimaryType());
              if (name.equals("operations") && operations != null) {
                // The items are the operations the selector supports.
                final ArrayNode values =
                    schema.putObject("items").put("type", "string").putArray("enum");
                operations.stream().sorted().forEach(operation -> values.add(operation.name()));
              }
              if (rejectedInEvery(raw, variants, variant -> variant.remove(name))) {
                required.add(name);
              }
              if ("array".equals(schema.path("type").asText())
                  && rejectedInEvery(raw, variants, variant -> variant.putArray(name))) {
                schema.put("minItems", 1);
              }
              if (schema.size() == 1
                  && "string".equals(schema.path("type").asText())
                  && rejectedInEvery(raw, variants, variant -> variant.put(name, ""))
                  && rejectedInEvery(raw, variants, variant -> variant.put(name, " "))) {
                schema.put("pattern", "\\S");
              }
              properties.set(name, schema);
            });
    if (!required.isEmpty()) {
      definition.set("required", required);
    }
    definition.put("additionalProperties", false);
  }

  /** The samples of {@code raw}, each checked to be accepted by the mapper. */
  private List<ObjectNode> variants(final Class<?> raw) {
    final List<String> samples = SAMPLES.get(raw);
    if (samples == null) {
      throw new AssertionError(
          "add a sample for " + raw.getSimpleName() + " to PlanSchemaTest.SAMPLES");
    }
    return samples.stream()
        .map(
            sample -> {
              final ObjectNode variant;
              try {
                variant = (ObjectNode) mapper.readTree(json(sample));
                mapper.readValue(variant.toString(), readAs(raw));
              } catch (final JsonProcessingException refused) {
                throw new AssertionError(
                    "the sample of " + raw.getSimpleName() + " is refused: " + sample, refused);
              }
              return variant;
            })
        .toList();
  }

  /**
   * Whether the mapper rejects every variant of {@code raw} once {@code change} is applied to it: a
   * constraint the schema may then declare without refusing a plan the agent accepts.
   */
  private boolean rejectedInEvery(
      final Class<?> raw, final List<ObjectNode> variants, final Consumer<ObjectNode> change) {
    for (final ObjectNode variant : variants) {
      final ObjectNode changed = variant.deepCopy();
      change.accept(changed);
      try {
        mapper.readValue(changed.toString(), readAs(raw));
        return false;
      } catch (final JsonProcessingException rejected) {
        // Rejected in this variant; try the next.
      }
    }
    return true;
  }

  /** A selector or an effect is read through its interface, which knows the {@code type} names. */
  private static Class<?> readAs(final Class<?> raw) {
    if (ChaosSelector.class.isAssignableFrom(raw)) {
      return ChaosSelector.class;
    }
    return ChaosEffect.class.isAssignableFrom(raw) ? ChaosEffect.class : raw;
  }

  private static Map.Entry<Class<?>, List<String>> samples(
      final Class<?> type, final String... json) {
    return Map.entry(type, List.of(json));
  }

  /** Writes JSON with single quotes for readability; they stand for double quotes. */
  private static String json(final String singleQuoted) {
    return singleQuoted.replace('\'', '"');
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
