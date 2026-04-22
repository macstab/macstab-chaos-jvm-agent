package com.macstab.chaos.startup;

import com.macstab.chaos.api.ChaosPlan;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

  // environment variable name for the config file path, where the file is loaded from.
  public static final String ENV_CONFIG_FILE = "MACSTAB_CHAOS_CONFIG_FILE";
  // environment variable name for the inline JSON config; the value is parsed directly as JSON
  // text.
  public static final String ENV_CONFIG_JSON = "MACSTAB_CHAOS_CONFIG_JSON";
  // environment variable name for the base64-encoded JSON config; the value is decoded from base64
  // and then parsed as JSON text.
  public static final String ENV_CONFIG_BASE64 = "MACSTAB_CHAOS_CONFIG_BASE64";
  // environment variable name for the debug dump flag; if set to "true" (case-insensitive) or if
  // the agent arg "debugDumpOnStart" is true, a diagnostic dump of the loaded plan is printed at
  // startup.
  public static final String ENV_DEBUG_DUMP = "MACSTAB_CHAOS_DEBUG_DUMP_ON_START";

  /** Maximum config file size: 1 MiB. Prevents OOM from oversized files. */
  private static final long MAX_FILE_SIZE = 1_048_576L;

  private static final String SOURCE_FILE_PREFIX = "file:";
  private static final String SOURCE_INLINE_JSON = "inline-json";
  private static final String SOURCE_BASE64 = "base64";
  private static final String TRUE_LITERAL = "true";
  private static final String FALSE_LITERAL = "false";

  /** Agent arg key carrying an inline JSON chaos plan. */
  private static final String ARG_CONFIG_JSON = "configJson";

  /** Agent arg key carrying a base64-encoded JSON chaos plan. */
  private static final String ARG_CONFIG_BASE64 = "configBase64";

  /** Agent arg key carrying a filesystem path to a JSON chaos plan. */
  private static final String ARG_CONFIG_FILE = "configFile";

  /** Agent arg key for opting into the startup-time plan dump. */
  private static final String ARG_DEBUG_DUMP_ON_START = "debugDumpOnStart";

  /** POSIX sticky-bit flag (01000) within the Unix mode word. */
  private static final int POSIX_STICKY_BIT = 01000;

  /** {@code java.nio.file} attribute-view name for POSIX filesystems. */
  private static final String ATTR_VIEW_POSIX = "posix";

  /** {@code java.nio.file} attribute name exposing the full Unix mode (sticky bit in high bits). */
  private static final String ATTR_UNIX_MODE = "unix:mode";

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
        firstNonBlank(agentArgs.get(ARG_CONFIG_JSON), environment.get(ENV_CONFIG_JSON));
    final String base64Json =
        firstNonBlank(agentArgs.get(ARG_CONFIG_BASE64), environment.get(ENV_CONFIG_BASE64));
    final String file =
        firstNonBlank(agentArgs.get(ARG_CONFIG_FILE), environment.get(ENV_CONFIG_FILE));

    final boolean debugDumpOnStart =
        agentArgs.getBoolean(ARG_DEBUG_DUMP_ON_START, false)
            || TRUE_LITERAL.equalsIgnoreCase(
                environment.getOrDefault(ENV_DEBUG_DUMP, FALSE_LITERAL));

    if (inlineJson != null) {
      final ChaosPlan plan = ChaosPlanMapper.read(inlineJson);
      return Optional.of(new LoadedPlan(plan, SOURCE_INLINE_JSON, debugDumpOnStart, null));
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
      decoded = Base64.getMimeDecoder().decode(base64Json);
    } catch (IllegalArgumentException exception) {
      throw new ConfigLoadException(
          "invalid base64 encoding in chaos plan configuration", SOURCE_BASE64, exception);
    }
    final String json = new String(decoded, StandardCharsets.UTF_8);
    final ChaosPlan plan = ChaosPlanMapper.read(json);
    return new LoadedPlan(plan, SOURCE_BASE64, debugDumpOnStart, null);
  }

  private static LoadedPlan loadFromFile(final String filePath, final boolean debugDumpOnStart) {
    final ValidatedRead read = readValidatedPlanFile(filePath);
    return new LoadedPlan(
        read.plan(), SOURCE_FILE_PREFIX + filePath, debugDumpOnStart, read.path());
  }

  /**
   * Reads and parses a chaos plan file with the same hardening applied to the JVM-agent config
   * path: normalised resolution, symlink rejection, 1 MiB size cap, and {@code NOFOLLOW_LINKS} read
   * to close the TOCTOU window between validation and open.
   *
   * <p>Intended for framework integrations (Spring starter, Quarkus extension) that accept an
   * operator-supplied plan path and must not bypass the agent's file-read hardening.
   *
   * @param filePath the file path to read
   * @return the parsed chaos plan
   * @throws ConfigLoadException if the path is unsafe, unreadable, oversize, or the JSON is invalid
   */
  public static ChaosPlan loadPlanFromFile(final String filePath) {
    return readValidatedPlanFile(filePath).plan();
  }

  private static ValidatedRead readValidatedPlanFile(final String filePath) {
    final Path path = validateAndResolvePath(filePath);
    final String json = readFileContents(path, filePath);
    final ChaosPlan plan = ChaosPlanMapper.read(json);
    return new ValidatedRead(plan, path);
  }

  /**
   * Opens the file at {@code path} with {@link LinkOption#NOFOLLOW_LINKS} and returns its full
   * contents as a UTF-8 string.
   *
   * <p>NOFOLLOW_LINKS on the read itself closes the TOCTOU window: {@code validateAndResolvePath}'s
   * symlink rejection ran on a previous syscall, so an attacker with directory write access could
   * swap the regular file for a symlink between validation and read. Opening the stream with
   * NOFOLLOW_LINKS makes the read atomic with the symlink check — if the path has since become a
   * symlink, the open fails.
   *
   * @param path resolved, validated file path to read
   * @param originalFilePath the original caller-supplied path, used in the error source label
   * @return full file contents decoded as UTF-8
   * @throws ConfigLoadException if the file cannot be opened or read
   */
  private static String readFileContents(final Path path, final String originalFilePath) {
    try (final InputStream inputStream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new ConfigLoadException(
          "failed to read chaos plan config file: " + path,
          SOURCE_FILE_PREFIX + originalFilePath,
          exception);
    }
  }

  private record ValidatedRead(ChaosPlan plan, Path path) {}

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
      throw new ConfigLoadException(
          "config file does not exist: " + path, SOURCE_FILE_PREFIX + filePath);
    }
    if (Files.isSymbolicLink(path)) {
      throw new ConfigLoadException(
          "config file is a symbolic link (rejected for safety): " + path,
          SOURCE_FILE_PREFIX + filePath);
    }
    if (!Files.isRegularFile(path)) {
      throw new ConfigLoadException(
          "config path is not a regular file: " + path, SOURCE_FILE_PREFIX + filePath);
    }
    rejectIfOversize(path, filePath);
    rejectWorldWritable(path, filePath);
    return path;
  }

  /**
   * Rejects files whose size exceeds {@link #MAX_FILE_SIZE}.
   *
   * <p>Reads the size with {@link LinkOption#NOFOLLOW_LINKS} rather than {@link Files#size(Path)}:
   * {@code Files.size} follows symlinks, so an attacker who flipped the path to a symlink between
   * the caller's symlink check and this size check would see the <em>target</em> file's size while
   * the eventual NOFOLLOW read opens a different file. Reading attributes with NOFOLLOW_LINKS
   * measures the link entry itself, keeping the check aligned with the eventual NOFOLLOW open in
   * {@link #readFileContents}.
   */
  private static void rejectIfOversize(final Path path, final String originalPath) {
    try {
      final long size =
          Files.readAttributes(
                  path,
                  java.nio.file.attribute.BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS)
              .size();
      if (size > MAX_FILE_SIZE) {
        throw new ConfigLoadException(
            "config file exceeds maximum size of "
                + MAX_FILE_SIZE
                + " bytes ("
                + size
                + " actual): "
                + path,
            SOURCE_FILE_PREFIX + originalPath);
      }
    } catch (IOException exception) {
      throw new ConfigLoadException(
          "cannot determine size of config file: " + path,
          SOURCE_FILE_PREFIX + originalPath,
          exception);
    }
  }

  /**
   * Rejects config files that are world-writable (POSIX {@code o+w}) or whose enclosing directory
   * is world-writable. Either condition lets any local user on the host swap the plan contents for
   * an attacker-chosen payload between deploys — activating chaos with scenarios the operator never
   * authorised. On non-POSIX filesystems the check is skipped: NTFS / ReFS express the same intent
   * through ACLs rather than mode bits, and we'd rather keep the agent usable on Windows test hosts
   * than fail-closed on a platform where the check is meaningless.
   */
  private static void rejectWorldWritable(final Path path, final String originalPath) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains(ATTR_VIEW_POSIX)) {
      return;
    }
    try {
      final Set<PosixFilePermission> filePerms =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      if (filePerms.contains(PosixFilePermission.OTHERS_WRITE)) {
        throw new ConfigLoadException(
            "config file is world-writable (rejected for safety): " + path,
            SOURCE_FILE_PREFIX + originalPath);
      }
      final Path parent = path.getParent();
      if (parent != null) {
        final Set<PosixFilePermission> dirPerms =
            Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
        // A world-writable parent without the sticky bit (t) lets any local user replace the
        // target file via unlink+create, even if the file itself is mode 0644. Sticky dirs
        // (typical for /tmp) restrict unlink to the file's owner so the replacement attack is
        // closed; accept those and reject the rest.
        if (dirPerms.contains(PosixFilePermission.OTHERS_WRITE) && !isStickyBitSet(parent)) {
          throw new ConfigLoadException(
              "config file's parent directory is world-writable without sticky bit (rejected"
                  + " for safety): "
                  + parent,
              SOURCE_FILE_PREFIX + originalPath);
        }
      }
    } catch (UnsupportedOperationException | IOException exception) {
      // Treat an unreadable POSIX view on a filesystem that claimed to support POSIX as an
      // I/O failure rather than silent success: the check exists precisely because the mode
      // bits matter.
      throw new ConfigLoadException(
          "cannot check POSIX permissions on config file: " + path,
          SOURCE_FILE_PREFIX + originalPath,
          exception);
    }
  }

  private static boolean isStickyBitSet(final Path directory) {
    // POSIX sticky bit is not exposed by Files.getPosixFilePermissions (only the nine rwxrwxrwx
    // mode bits are). Use the Unix-specific "unix:mode" attribute view where available, which
    // returns the full 16-bit mode including setuid/setgid/sticky in the high bits. On
    // filesystems that don't expose it, assume sticky is NOT set — fail-closed.
    try {
      final Object mode = Files.getAttribute(directory, ATTR_UNIX_MODE, LinkOption.NOFOLLOW_LINKS);
      if (mode instanceof Integer modeValue) {
        return (modeValue & POSIX_STICKY_BIT) != 0;
      }
    } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
      // Fall through to fail-closed.
    }
    return false;
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
