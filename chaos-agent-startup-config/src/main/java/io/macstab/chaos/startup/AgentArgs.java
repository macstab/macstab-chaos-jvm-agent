package io.macstab.chaos.startup;

import java.util.Map;

public record AgentArgs(Map<String, String> values) {
  public String get(String key) {
    return values.get(key);
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String value = values.get(key);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }
}
