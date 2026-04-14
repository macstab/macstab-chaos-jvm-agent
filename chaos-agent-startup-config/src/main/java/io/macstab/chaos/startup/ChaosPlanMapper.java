package io.macstab.chaos.startup;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.macstab.chaos.api.ChaosPlan;

public final class ChaosPlanMapper {
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private ChaosPlanMapper() {}

  public static ChaosPlan read(String json) {
    try {
      return OBJECT_MAPPER.readValue(json, ChaosPlan.class);
    } catch (Exception exception) {
      throw new IllegalArgumentException("failed to parse chaos plan JSON", exception);
    }
  }

  public static String write(ChaosPlan chaosPlan) {
    try {
      return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(chaosPlan);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to serialize chaos plan", exception);
    }
  }
}
