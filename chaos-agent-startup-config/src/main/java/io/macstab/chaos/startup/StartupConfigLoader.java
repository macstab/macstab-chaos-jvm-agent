package io.macstab.chaos.startup;

import io.macstab.chaos.api.ChaosPlan;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public final class StartupConfigLoader {
  public static final String ENV_CONFIG_FILE = "MACSTAB_CHAOS_CONFIG_FILE";
  public static final String ENV_CONFIG_JSON = "MACSTAB_CHAOS_CONFIG_JSON";
  public static final String ENV_CONFIG_BASE64 = "MACSTAB_CHAOS_CONFIG_BASE64";
  public static final String ENV_DEBUG_DUMP = "MACSTAB_CHAOS_DEBUG_DUMP_ON_START";

  private StartupConfigLoader() {}

  public static Optional<LoadedPlan> load(String rawAgentArgs, Map<String, String> environment) {
    AgentArgs agentArgs = AgentArgsParser.parse(rawAgentArgs);
    String inlineJson =
        firstNonBlank(agentArgs.get("configJson"), environment.get(ENV_CONFIG_JSON));
    String base64Json =
        firstNonBlank(agentArgs.get("configBase64"), environment.get(ENV_CONFIG_BASE64));
    String file = firstNonBlank(agentArgs.get("configFile"), environment.get(ENV_CONFIG_FILE));
    boolean debugDumpOnStart =
        agentArgs.getBoolean("debugDumpOnStart", false)
            || Boolean.parseBoolean(environment.getOrDefault(ENV_DEBUG_DUMP, "false"));
    if (inlineJson != null) {
      return Optional.of(
          new LoadedPlan(
              ChaosPlanMapper.read(inlineJson), "agent-args:inline-json", debugDumpOnStart));
    }
    if (base64Json != null) {
      byte[] decoded = Base64.getDecoder().decode(base64Json);
      String json = new String(decoded, StandardCharsets.UTF_8);
      return Optional.of(
          new LoadedPlan(ChaosPlanMapper.read(json), "agent-args:inline-base64", debugDumpOnStart));
    }
    if (file != null) {
      try {
        String json = Files.readString(Path.of(file));
        return Optional.of(
            new LoadedPlan(ChaosPlanMapper.read(json), "file:" + file, debugDumpOnStart));
      } catch (Exception exception) {
        throw new IllegalArgumentException("failed to load startup config file " + file, exception);
      }
    }
    return Optional.empty();
  }

  private static String firstNonBlank(String left, String right) {
    if (left != null && !left.isBlank()) {
      return left;
    }
    if (right != null && !right.isBlank()) {
      return right;
    }
    return null;
  }

  public record LoadedPlan(ChaosPlan plan, String source, boolean debugDumpOnStart) {}
}
