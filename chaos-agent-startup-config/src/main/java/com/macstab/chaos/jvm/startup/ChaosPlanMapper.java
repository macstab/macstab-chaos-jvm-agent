package com.macstab.chaos.jvm.startup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.Internal;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/**
 * Jackson-based serialisation bridge between JSON and {@link ChaosPlan}.
 *
 * <p>The mapper is configured for strict parsing:
 *
 * <ul>
 *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} — rejects unexpected fields
 *   <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} — rejects JSON with trailing garbage
 *   <li>{@link StreamReadFeature#STRICT_DUPLICATE_DETECTION} — rejects a property given twice
 *       instead of keeping the last value
 *   <li>no coercion: a number is not a boolean, a string or a fraction is not an integer, and an
 *       enum is read by name only
 *   <li>durations and instants are ISO-8601 strings only ({@code "PT0.1S"}, {@code
 *       "2026-11-01T08:00:00Z"}); a number is refused (Jackson's own deserializers read one as
 *       seconds whatever their leniency)
 *   <li>{@link StreamReadConstraints} — caps nesting depth and single-string length at the
 *       streaming parser level; a pathological payload like {@code "[[[[...]}]]]...] }} cannot
 *       exhaust the stack or allocate an arbitrarily large String before the outer size gate sees
 *       it
 * </ul>
 *
 * <p>Input size is validated before parsing to prevent OOM from oversized payloads. Durations and
 * instants are written in ISO-8601; {@link #write(ChaosPlan)} leaves out absent ({@code null})
 * values.
 */
public final class ChaosPlanMapper {

  /**
   * Maximum JSON input size accepted by {@link #read(String)}, in UTF-8 bytes: 1 MiB. Also the cap
   * on a plan file and on a plan posted to a management endpoint.
   */
  @Internal public static final int MAX_JSON_LENGTH = 1_048_576;

  /** Cap, in characters, on a validation message relayed into a parse failure. */
  private static final int CAUSE_MESSAGE_MAX_LENGTH = 200;

  /**
   * Maximum JSON nesting depth allowed by the streaming parser. 32 is generous for any realistic
   * chaos plan (scenarios rarely exceed depth 6 — plan → scenarios → selector → network → ops)
   * while still bounding worst-case stack usage at roughly 32 stack frames per token.
   */
  static final int MAX_NESTING_DEPTH = 32;

  /**
   * Maximum length of a single JSON string token, in chars. Equal to the outer 1 MiB size gate so
   * no single embedded string can exceed the whole-payload budget. Protects against payloads that
   * embed a multi-hundred-MB string in a tiny-looking envelope.
   */
  static final int MAX_STRING_LENGTH = MAX_JSON_LENGTH;

  private static final String SOURCE_JSON_INPUT = "json-input";

  /** The name of the plan {@link #read(String)} wraps bare scenarios in. */
  private static final String DEFAULT_PLAN_NAME = "default";

  /** A byte order mark, which a file saved by some editors starts with. */
  private static final char BYTE_ORDER_MARK = '\uFEFF';

  /** Members that make an object a plan; an object without any of them is a scenario. */
  private static final List<String> PLAN_MEMBERS =
      List.of("scenarios", "metadata", "observability");

  private static final TypeReference<List<ChaosScenario>> SCENARIO_LIST = new TypeReference<>() {};

  /** Highest code point representable in a single UTF-8 byte (7-bit ASCII). */
  private static final int UTF8_ONE_BYTE_LIMIT = 0x80;

  /** Highest code point representable in two UTF-8 bytes (U+0000–U+07FF). */
  private static final int UTF8_TWO_BYTE_LIMIT = 0x800;

  private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

  /** The mapper {@link #write(ChaosPlan)} uses: absent values are left out of a plan. */
  private static final ObjectMapper PLAN_WRITER =
      OBJECT_MAPPER.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

  private ChaosPlanMapper() {}

  /** The configured mapper: the plan schema is derived from it ({@code PlanSchemaTest}). */
  static ObjectMapper objectMapper() {
    return OBJECT_MAPPER;
  }

  private static ObjectMapper buildObjectMapper() {
    final JsonFactory factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    return ApiJsonMixins.register(JsonMapper.builder(factory))
        .addModule(new JavaTimeModule())
        // After JavaTimeModule: a module added later replaces its Duration and Instant readers.
        .addModule(
            new SimpleModule("chaos-plan-iso-time")
                .addDeserializer(
                    Duration.class,
                    new IsoStringDeserializer<>(Duration.class, Duration::parse, "duration"))
                .addDeserializer(
                    Instant.class,
                    new IsoStringDeserializer<>(Instant.class, Instant::parse, "instant")))
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .build();
  }

  /** Reads a value from an ISO-8601 string only; a number or any other token is refused. */
  private static final class IsoStringDeserializer<T> extends StdScalarDeserializer<T> {

    private static final long serialVersionUID = 1L;

    private final transient Function<String, T> parse;
    private final String kind;

    IsoStringDeserializer(final Class<T> type, final Function<String, T> parse, final String kind) {
      super(type);
      this.parse = parse;
      this.kind = kind;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(final JsonParser parser, final DeserializationContext context)
        throws IOException {
      if (!parser.hasToken(JsonToken.VALUE_STRING)) {
        return (T) context.handleUnexpectedToken(handledType(), parser);
      }
      final String text = parser.getText().trim();
      try {
        return parse.apply(text);
      } catch (final DateTimeParseException invalid) {
        return (T) context.handleWeirdStringValue(handledType(), text, "invalid " + kind);
      }
    }
  }

  /**
   * Deserialises a plan, a single scenario or an array of scenarios, recognised by the JSON's
   * structure: an object with a {@code scenarios}, {@code metadata} or {@code observability} member
   * is a plan, another object a scenario. Scenarios are wrapped in a plan named {@value
   * #DEFAULT_PLAN_NAME}. A leading byte order mark is skipped.
   *
   * @param json non-null JSON representation of a chaos plan
   * @return validated ChaosPlan instance
   * @throws ConfigLoadException if the JSON is malformed, exceeds size limits, or violates the plan
   *     contract
   */
  public static ChaosPlan read(final String json) {
    return read(json, SOURCE_JSON_INPUT);
  }

  /**
   * Deserialises a plan as {@link #read(String)} does, reporting a failure with {@code source} (for
   * example the plan file).
   *
   * @param json non-null JSON representation of a chaos plan
   * @param source where the JSON came from, as {@link ConfigLoadException#getSource()} reports it
   * @return validated ChaosPlan instance
   * @throws ConfigLoadException if the JSON is malformed, exceeds size limits, or violates the plan
   *     contract
   */
  static ChaosPlan read(final String json, final String source) {
    return read(json, source, DEFAULT_PLAN_NAME);
  }

  private static ChaosPlan read(final String input, final String source, final String planName) {
    final String json =
        !input.isEmpty() && input.charAt(0) == BYTE_ORDER_MARK ? input.substring(1) : input;
    // String.length() counts UTF-16 chars, not bytes — a 500k-char JSON full of 4-byte
    // codepoints is ~2 MiB encoded, silently bypassing the documented 1 MiB gate. Count the
    // actual UTF-8 byte length with a short-circuit pass so oversized inputs are rejected
    // without a full byte[] allocation.
    final int utf8Bytes = utf8ByteLengthCapped(json, MAX_JSON_LENGTH + 1);
    if (utf8Bytes > MAX_JSON_LENGTH) {
      throw new ConfigLoadException(
          "chaos plan JSON exceeds maximum size of "
              + MAX_JSON_LENGTH
              + " bytes (>"
              + MAX_JSON_LENGTH
              + " UTF-8 bytes provided)",
          source);
    }
    // The shape is read from the tree; each shape is bound from the original text, so a failure
    // reports its position in the input.
    try {
      final JsonNode tree = OBJECT_MAPPER.readTree(json);
      if (tree.isArray()) {
        return wrap(OBJECT_MAPPER.readValue(json, SCENARIO_LIST), planName, source);
      }
      if (tree.isObject() && PLAN_MEMBERS.stream().noneMatch(tree::has)) {
        return wrap(List.of(OBJECT_MAPPER.readValue(json, ChaosScenario.class)), planName, source);
      }
      // A plan, or neither an array nor an object: bound as a plan, as before.
      final ChaosPlan plan = OBJECT_MAPPER.readValue(json, ChaosPlan.class);
      // Jackson maps the JSON literal null to a null value rather than failing.
      if (plan == null) {
        throw new ConfigLoadException(
            "chaos plan JSON is null; expected an object with \"scenarios\"", source);
      }
      return plan;
    } catch (JsonProcessingException exception) {
      final com.fasterxml.jackson.core.JsonLocation location = exception.getLocation();
      final boolean positioned = location != null && location.getLineNr() > 0;
      throw new ConfigLoadException(
          parseFailureMessage(exception),
          source,
          exception,
          positioned ? location.getLineNr() : 0,
          positioned ? location.getColumnNr() : 0,
          parseFailureReason(exception));
    }
  }

  /** Wraps bare scenarios into a plan named {@code planName}; the plan's own rules apply. */
  private static ChaosPlan wrap(
      final List<ChaosScenario> scenarios, final String planName, final String source) {
    try {
      return new ChaosPlan(
          new ChaosPlan.Metadata(planName, ""),
          new ChaosPlan.Observability(true, true, false),
          scenarios);
    } catch (final IllegalArgumentException invalid) {
      final String reason =
          "invalid value: " + LogText.sanitize(invalid.getMessage(), CAUSE_MESSAGE_MAX_LENGTH);
      throw new ConfigLoadException(
          "failed to parse chaos plan JSON: " + reason, source, null, 0, 0, reason);
    }
  }

  /**
   * Describes a parse failure by position, kind and JSON path, for example {@code "failed to parse
   * chaos plan JSON at line 7, column 21: unknown property 'delayy' at scenarios[0].effect"}.
   * Values from the input are never included — Jackson's own messages can quote them (a mistyped
   * value would be echoed into logs), so only field names, which come from the plan's structure,
   * are reported.
   */
  static String parseFailureMessage(final JsonProcessingException exception) {
    final StringBuilder message = new StringBuilder("failed to parse chaos plan JSON");
    final com.fasterxml.jackson.core.JsonLocation location = exception.getLocation();
    if (location != null && location.getLineNr() > 0) {
      message
          .append(" at line ")
          .append(location.getLineNr())
          .append(", column ")
          .append(location.getColumnNr());
    }
    return message.append(": ").append(parseFailureReason(exception)).toString();
  }

  /** The failure without its position: the kind and the JSON path. */
  static String parseFailureReason(final JsonProcessingException exception) {
    final String path =
        switch (exception) {
          case com.fasterxml.jackson.databind.JsonMappingException mapping -> jsonPath(mapping);
          case com.fasterxml.jackson.core.JsonParseException syntax -> parserPath(syntax);
          default -> "";
        };
    return path.isEmpty() ? failureKind(exception) : failureKind(exception) + " at " + path;
  }

  private static String failureKind(final JsonProcessingException exception) {
    if (exception
        instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException unrecognized) {
      return "unknown property '" + unrecognized.getPropertyName() + "'";
    }
    if (exception instanceof com.fasterxml.jackson.databind.exc.InvalidTypeIdException) {
      return "unknown or missing type";
    }
    if (exception
        instanceof com.fasterxml.jackson.databind.exc.ValueInstantiationException instantiation) {
      // The cause is the agent's own validation message (for example "delay bounds must be
      // non-null"), not Jackson's description of the input. It can quote a field value (a
      // duplicate id), so it is sanitised and capped.
      final Throwable cause = instantiation.getCause();
      return cause != null && cause.getMessage() != null
          ? "invalid value: " + LogText.sanitize(cause.getMessage(), CAUSE_MESSAGE_MAX_LENGTH)
          : "invalid value";
    }
    if (exception instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
      return "value of the wrong type";
    }
    // Syntax errors are found while the tree is read, before any value is bound.
    if (exception instanceof com.fasterxml.jackson.core.JsonParseException) {
      return "malformed JSON";
    }
    return exception.getClass().getSimpleName();
  }

  private static String jsonPath(
      final com.fasterxml.jackson.databind.JsonMappingException mapping) {
    final StringBuilder path = new StringBuilder();
    java.util.List<com.fasterxml.jackson.databind.JsonMappingException.Reference> references =
        mapping.getPath();
    if (mapping instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
        && !references.isEmpty()) {
      // The last reference is the unknown property itself, which the kind already names: the
      // path is the object that holds it.
      references = references.subList(0, references.size() - 1);
    }
    for (final com.fasterxml.jackson.databind.JsonMappingException.Reference reference :
        references) {
      if (reference.getFieldName() != null) {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.toString();
  }

  /**
   * The path to where a syntax error stopped the parser: the members and indices of the values it
   * was in.
   */
  private static String parserPath(final com.fasterxml.jackson.core.JsonParseException syntax) {
    // Member names and array indices, outermost first.
    final java.util.Deque<Object> steps = new java.util.ArrayDeque<>();
    final com.fasterxml.jackson.core.JsonParser parser = syntax.getProcessor();
    for (com.fasterxml.jackson.core.JsonStreamContext context =
            parser == null ? null : parser.getParsingContext();
        context != null;
        context = context.getParent()) {
      if (context.hasCurrentName()) {
        steps.addFirst(context.getCurrentName());
      } else if (context.inArray()) {
        steps.addFirst(context.getCurrentIndex());
      }
    }
    final StringBuilder path = new StringBuilder();
    for (final Object step : steps) {
      if (step instanceof Integer index) {
        path.append('[').append(index).append(']');
      } else {
        if (!path.isEmpty()) {
          path.append('.');
        }
        path.append(step);
      }
    }
    return path.toString();
  }

  /**
   * Returns the UTF-8 byte length of {@code s}, but stops counting once the result reaches or
   * exceeds {@code cap}. The returned value is exact when below {@code cap}; otherwise it is simply
   * {@code >= cap}.
   */
  static int utf8ByteLengthCapped(final CharSequence text, final int cap) {
    int bytes = 0;
    final int length = text.length();
    for (int i = 0; i < length; i++) {
      final char ch = text.charAt(i);
      if (ch < UTF8_ONE_BYTE_LIMIT) {
        bytes += 1;
      } else if (ch < UTF8_TWO_BYTE_LIMIT) {
        bytes += 2;
      } else if (Character.isHighSurrogate(ch)) {
        // Only a well-formed surrogate pair encodes to 4 UTF-8 bytes. A lone high surrogate —
        // either at the end of the string or followed by a non-low-surrogate — gets replaced
        // with U+FFFD at encode time, which is 3 bytes, and the next char must still be counted
        // on its own. Consuming it unconditionally (`i++` regardless) would over-count lone
        // surrogates by one byte each and under-count the following CJK char by three, shifting
        // the net tally far enough off to let ~50% oversized JSON slip past the 1 MiB guard.
        if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
          bytes += 4;
          i++;
        } else {
          bytes += 3;
        }
      } else {
        bytes += 3;
      }
      if (bytes >= cap) {
        return bytes;
      }
    }
    return bytes;
  }

  /**
   * Serialises a {@link ChaosPlan} to a pretty-printed JSON string, leaving out absent values.
   *
   * @param plan non-null plan to serialise
   * @return JSON representation
   * @throws IllegalStateException if serialisation fails (indicates a bug)
   */
  public static String write(final ChaosPlan plan) {
    try {
      return PLAN_WRITER.writerWithDefaultPrettyPrinter().writeValueAsString(plan);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("failed to serialize chaos plan", exception);
    }
  }

  /**
   * Serialises any value the plan model and diagnostics consist of (records, enums, durations,
   * instants) to compact JSON, with the same conventions as plans.
   *
   * @param value the value
   * @return JSON
   * @throws IllegalStateException if serialisation fails (indicates a bug)
   */
  public static String toJson(final Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "failed to serialize " + value.getClass().getName(), exception);
    }
  }

  /**
   * Reads a plan, a single scenario or an array of scenarios as {@link #read(String)} does,
   * wrapping bare scenarios in a plan named {@code planName}.
   *
   * @param json the JSON
   * @param planName the name of the plan wrapping bare scenarios
   * @return the plan
   * @throws ConfigLoadException if the JSON is malformed or invalid
   */
  public static ChaosPlan readPlanOrScenarios(final String json, final String planName) {
    return read(json, SOURCE_JSON_INPUT, planName);
  }
}
