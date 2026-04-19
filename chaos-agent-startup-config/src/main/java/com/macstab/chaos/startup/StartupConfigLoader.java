package com.macstab.chaos.startup;

import com.macstab.chaos.api.ChaosPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves and loads a {@link ChaosPlan} during JVM agent startup.
 *
 * <p>Configuration sources are checked in priority order (first match wins):
 *
 * <ol>
 *   <li><b>Inline JSON</b> — agent arg {@code configJson} or env {@code MACSTAB_CHAOS_CONFIG_JSON}
 *   <li><b>Base64-encoded JSON</b> — agent arg {@code configBase64} or env {@code
 *       MACSTAB_CHAOS_CONFIG_BASE64}
 *   <li><b>File path</b> — agent arg {@code configFile} or env {@code MACSTAB_CHAOS_CONFIG_FILE}
 * </ol>
 *
 * <p>Within each source type, the agent arg takes precedence over the environment variable.
 *
 * <p>If no source is configured, {@link #load} returns empty — the agent starts with no active
 * scenarios.
 */
public final class StartupConfigLoader {

  public static final String ENV_CONFIG_FILE = "MACSTAB_CHAOS_CONFIG_FILE";
  public static final String ENV_CONFIG_JSON = "MACSTAB_CHAOS_CONFIG_JSON";
  public static final String ENV_CONFIG_BASE64 = "MACSTAB_CHAOS_CONFIG_BASE64";
  public static final String ENV_DEBUG_DUMP = "MACSTAB_CHAOS_DEBUG_DUMP_ON_START";

  /** Maximum config file size: 1 MiB. Prevents OOM from oversized files. */
  private static final long MAX_FILE_SIZE = 1_048_576L;

  private StartupConfigLoader() {}

  /**
   * Loads a chaos plan from the first available configuration source.
   *
   * @param rawAgentArgs raw argument string from {@code -javaagent:}, may be null
   * @param environment environment variable map (typically {@link System#getenv()})
   * @return loaded plan with source metadata, or empty if no config is present
   * @throws ConfigLoadException if a source is specified but cannot be read or parsed
   */
  public static Optional<LoadedPlan> load(
      final String rawAgentArgs, final Map<String, String> environment) {
    final AgentArgs agentArgs = AgentArgsParser.parse(rawAgentArgs);

    final String inlineJson =
        firstNonBlank(agentArgs.get("configJson"), environment.get(ENV_CONFIG_JSON));
    final String base64Json =
        firstNonBlank(agentArgs.get("configBase64"), environment.get(ENV_CONFIG_BASE64));
    final String file =
        firstNonBlank(agentArgs.get("configFile"), environment.get(ENV_CONFIG_FILE));

    final boolean debugDumpOnStart =
        agentArgs.getBoolean("debugDumpOnStart", false)
            || "true".equalsIgnoreCase(environment.getOrDefault(ENV_DEBUG_DUMP, "false"));

    if (inlineJson != null) {
      final ChaosPlan plan = ChaosPlanMapper.read(inlineJson);
      return Optional.of(new LoadedPlan(plan, "inline-json", debugDumpOnStart, null));
    }
    if (base64Json != null) {
      return Optional.of(loadFromBase64(base64Json, debugDumpOnStart));
    }
    if (file != null) {
      return Optional.of(loadFromFile(file, debugDumpOnStart));
    }
    return Optional.empty();
  }

  private static LoadedPlan loadFromBase64(
      final String base64Json, final boolean debugDumpOnStart) {
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64Json);
    } catch (IllegalArgumentException exception) {
      throw new ConfigLoadException(
          "invalid base64 encoding in chaos plan configuration", "base64", exception);
    }
    final String json = new String(decoded, StandardCharsets.UTF_8);
    final ChaosPlan plan = ChaosPlanMapper.read(json);
    return new LoadedPlan(plan, "base64", debugDumpOnStart, null);
  }

  private static LoadedPlan loadFromFile(final String filePath, final boolean debugDumpOnStart) {
    final Path path = validateAndResolvePath(filePath);
    final String json;
    // NOFOLLOW_LINKS on the read itself closes the TOCTOU window: validateAndResolvePath's
    // symlink rejection ran on a previous syscall, so an attacker with directory write access
    // could swap the regular file for a symlink between validation and read. Opening the
    // stream with NOFOLLOW_LINKS makes the read atomic with the symlink check — if the path
    // has since become a symlink, the open fails.
    try (final java.io.InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new ConfigLoadException(
          "failed to read chaos plan config file: " + path, "file:" + filePath, exception);
    }
    final ChaosPlan plan = ChaosPlanMapper.read(json);
    return new LoadedPlan(plan, "file:" + filePath, debugDumpOnStart, path);
  }

  /**
   * Validates the file path against basic safety constraints.
   *
   * <p>{@link Path#normalize()} resolves all {@code ..} and {@code .} path components before any
   * check is applied, neutralising traversal sequences. Symlink rejection is the primary
   * path-safety control on the resolved path.
   *
   * @throws ConfigLoadException if the path is unsafe, a symlink, or exceeds size limits
   */
  private static Path validateAndResolvePath(final String filePath) {
    final Path path = Path.of(filePath).toAbsolutePath().normalize();

    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConfigLoadException("config file does not exist: " + path, "file:" + filePath);
    }
    if (Files.isSymbolicLink(path)) {
      throw new ConfigLoadException(
          "config file is a symbolic link (rejected for safety): " + path, "file:" + filePath);
    }
    if (!Files.isRegularFile(path)) {
      throw new ConfigLoadException(
          "config path is not a regular file: " + path, "file:" + filePath);
    }
    try {
      final long size = Files.size(path);
      if (size > MAX_FILE_SIZE) {
        throw new ConfigLoadException(
            "config file exceeds maximum size of "
                + MAX_FILE_SIZE
                + " bytes ("
                + size
                + " actual): "
                + path,
            "file:" + filePath);
      }
    } catch (IOException exception) {
      throw new ConfigLoadException(
          "cannot determine size of config file: " + path, "file:" + filePath, exception);
    }
    return path;
  }

  private static String firstNonBlank(final String left, final String right) {
    if (left != null && !left.isBlank()) {
      return left;
    }
    if (right != null && !right.isBlank()) {
      return right;
    }
    return null;
  }

  /**
   * Result of successfully loading a chaos plan from a configuration source.
   *
   * @param plan the parsed chaos plan
   * @param source human-readable source identifier (e.g. {@code "file:/etc/chaos/plan.json"})
   * @param debugDumpOnStart whether to print a diagnostic dump at startup
   * @param filePath the resolved file path when the source was a config file, {@code null}
   *     otherwise; used by the bootstrap poller to set up file watching
   */
  public record LoadedPlan(
      ChaosPlan plan, String source, boolean debugDumpOnStart, Path filePath) {}
}
