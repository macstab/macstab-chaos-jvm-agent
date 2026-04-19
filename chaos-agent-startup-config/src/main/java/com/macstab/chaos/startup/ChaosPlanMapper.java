package com.macstab.chaos.startup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.macstab.chaos.api.ChaosPlan;

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

  private static final ObjectMapper OBJECT_MAPPER = buildObjectMapper();

  private ChaosPlanMapper() {}

  private static ObjectMapper buildObjectMapper() {
    final JsonFactory factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .build())
            .build();
    return JsonMapper.builder(factory)
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
          "json-input");
    }
    try {
      return OBJECT_MAPPER.readValue(json, ChaosPlan.class);
    } catch (JsonProcessingException exception) {
      throw new ConfigLoadException("failed to parse chaos plan JSON", "json-input", exception);
    }
  }

  /**
   * Returns the UTF-8 byte length of {@code s}, but stops counting once the result reaches or
   * exceeds {@code cap}. The returned value is exact when below {@code cap}; otherwise it is simply
   * {@code >= cap}.
   */
  static int utf8ByteLengthCapped(final CharSequence s, final int cap) {
    int bytes = 0;
    final int n = s.length();
    for (int i = 0; i < n; i++) {
      final char c = s.charAt(i);
      if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)) {
        // Full UTF-8 encoding of a surrogate pair is 4 bytes; consume the low surrogate too.
        bytes += 4;
        i++;
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
}
