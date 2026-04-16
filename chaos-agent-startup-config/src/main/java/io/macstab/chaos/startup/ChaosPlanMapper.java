package io.macstab.chaos.startup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.macstab.chaos.api.ChaosPlan;

/**
 * Jackson-based serialisation bridge between JSON and {@link ChaosPlan}.
 *
 * <p>The mapper is configured for strict parsing:
 *
 * <ul>
 *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} — rejects unexpected fields
 *   <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} — rejects JSON with trailing garbage
 * </ul>
 *
 * <p>Input size is validated before parsing to prevent OOM from oversized payloads.
 */
public final class ChaosPlanMapper {

  /** Maximum JSON input size accepted by {@link #read(String)}: 1 MiB. */
  static final int MAX_JSON_LENGTH = 1_048_576;

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private ChaosPlanMapper() {}

  /**
   * Deserialises a JSON string into a {@link ChaosPlan}.
   *
   * @param json non-null JSON representation of a chaos plan
   * @return validated ChaosPlan instance
   * @throws ConfigLoadException if the JSON is malformed, exceeds size limits, or violates the plan
   *     contract
   */
  public static ChaosPlan read(final String json) {
    if (json.length() > MAX_JSON_LENGTH) {
      throw new ConfigLoadException(
          "chaos plan JSON exceeds maximum size of "
              + MAX_JSON_LENGTH
              + " bytes ("
              + json.length()
              + " provided)",
          "json-input");
    }
    try {
      return OBJECT_MAPPER.readValue(json, ChaosPlan.class);
    } catch (JsonProcessingException exception) {
      throw new ConfigLoadException("failed to parse chaos plan JSON", "json-input", exception);
    }
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
