package com.macstab.chaos.jvm.startup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.macstab.chaos.jvm.api.ChaosPlan;

/**
 * Jackson-based serialisation bridge between JSON and {@link ChaosPlan}.
 *
 * <p>The mapper is configured for strict parsing:
 *
 * <ul>
 *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} — rejects unexpected fields
 *   <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} — rejects JSON with trailing garbage
 *   <li>{@link StreamReadConstraints} — caps nesting depth and single-string length at the
 *       streaming parser level; a pathological payload like {@code "[[[[...]}]]]...] }} cannot
 *       exhaust the stack or allocate an arbitrarily large String before the outer size gate sees
 *       it
 * </ul>
 *
 * <p>Input size is validated before parsing to prevent OOM from oversized payloads.
 */
public final class ChaosPlanMapper {

  /** Maximum JSON input size accepted by {@link #read(String)}: 1 MiB. */
  static final int MAX_JSON_LENGTH = 1_048_576;

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

  /** Highest code point representable in a single UTF-8 byte (7-bit ASCII). */
  private static final int UTF8_ONE_BYTE_LIMIT = 0x80;

  /** Highest code point representable in two UTF-8 bytes (U+0000–U+07FF). */
  private static final int UTF8_TWO_BYTE_LIMIT = 0x800;

  private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

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
            .build();
    return ApiJsonMixins.register(JsonMapper.builder(factory))
        .addModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  /**
   * Deserialises a JSON string into a {@link ChaosPlan}.
   *
   * @param json non-null JSON representation of a chaos plan
   * @return validated ChaosPlan instance
   * @throws ConfigLoadException if the JSON is malformed, exceeds size limits, or violates the plan
   *     contract
   */
  public static ChaosPlan read(final String json) {
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
          SOURCE_JSON_INPUT);
    }
    final ChaosPlan plan;
    try {
      plan = OBJECT_MAPPER.readValue(json, ChaosPlan.class);
    } catch (JsonProcessingException exception) {
      final com.fasterxml.jackson.core.JsonLocation location = exception.getLocation();
      final boolean positioned = location != null && location.getLineNr() > 0;
      throw new ConfigLoadException(
          parseFailureMessage(exception),
          SOURCE_JSON_INPUT,
          exception,
          positioned ? location.getLineNr() : 0,
          positioned ? location.getColumnNr() : 0,
          parseFailureReason(exception));
    }
    // Jackson maps the JSON literal null to a null value rather than failing.
    if (plan == null) {
      throw new ConfigLoadException(
          "chaos plan JSON is null; expected an object with \"scenarios\"", SOURCE_JSON_INPUT);
    }
    return plan;
  }

  /**
   * Describes a parse failure by position, kind and JSON path, for example {@code "failed to parse
   * chaos plan JSON at line 7, column 21: unknown property 'delayy' at scenarios[0].effect"}.
   * Values from the input are never included — Jackson's own messages can quote them (a mistyped
   * value would be echoed into logs), so only field names, which come from the plan's structure,
   * are reported. An earlier version reported only "failed to parse chaos plan JSON".
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
    message.append(": ").append(failureKind(exception));
    if (exception instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
      final String path = jsonPath(mapping);
      if (!path.isEmpty()) {
        message.append(" at ").append(path);
      }
    }
    return message.toString();
  }

  /** The failure without its position: the kind and, for mapping failures, the JSON path. */
  static String parseFailureReason(final JsonProcessingException exception) {
    final StringBuilder reason = new StringBuilder(failureKind(exception));
    if (exception instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
      final String path = jsonPath(mapping);
      if (!path.isEmpty()) {
        reason.append(" at ").append(path);
      }
    }
    return reason.toString();
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
      // non-null"), not Jackson's description of the input.
      final Throwable cause = instantiation.getCause();
      return cause != null && cause.getMessage() != null
          ? "invalid value: " + cause.getMessage()
          : "invalid value";
    }
    if (exception instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
      return "value of the wrong type";
    }
    // A syntax error inside a value being bound arrives wrapped in a mapping exception.
    if (exception instanceof com.fasterxml.jackson.core.JsonParseException
        || exception.getCause() instanceof com.fasterxml.jackson.core.JsonParseException) {
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
        // on its own. Consuming it unconditionally (the old code did `i++` regardless) over-
        // counted lone surrogates by one byte each and under-counted the following CJK char by
        // three, shifting the net tally far enough off to let ~50% oversized JSON slip past the
        // 1 MiB guard on JDK 17/21.
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
   * Serialises a {@link ChaosPlan} to a pretty-printed JSON string.
   *
   * @param plan non-null plan to serialise
   * @return JSON representation
   * @throws IllegalStateException if serialisation fails (indicates a bug)
   */
  public static String write(final ChaosPlan plan) {
    try {
      return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan);
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
   * Reads a plan, a single scenario or an array of scenarios, recognised by the JSON's structure
   * (an object with a {@code scenarios} member is a plan). Scenarios are wrapped in a plan named
   * {@code planName}.
   *
   * @param json the JSON
   * @param planName the name of the plan wrapping bare scenarios
   * @return the plan
   * @throws ConfigLoadException if the JSON is malformed or invalid
   */
  public static ChaosPlan readPlanOrScenarios(final String json, final String planName) {
    final com.fasterxml.jackson.databind.JsonNode tree;
    try {
      tree = OBJECT_MAPPER.readTree(json);
    } catch (JsonProcessingException exception) {
      return read(json); // reports the syntax error with its position
    }
    if (tree != null && tree.isObject() && tree.has("scenarios")) {
      return read(json);
    }
    final com.fasterxml.jackson.databind.node.ObjectNode plan = OBJECT_MAPPER.createObjectNode();
    plan.putObject("metadata").put("name", planName);
    if (tree != null && tree.isArray()) {
      plan.set("scenarios", tree);
    } else {
      plan.putArray("scenarios").add(tree);
    }
    return read(plan.toString());
  }
}
