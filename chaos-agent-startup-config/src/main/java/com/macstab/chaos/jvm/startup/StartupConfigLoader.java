package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.Internal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

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
 * <p>Within each source type, the agent arg takes precedence over the system property ({@code
 * macstab.chaos.configJson}, {@code macstab.chaos.configBase64}, {@code macstab.chaos.configFile}),
 * which takes precedence over the environment variable.
 *
 * <p>If no source is configured, {@link #load} returns empty — the agent starts with no active
 * scenarios.
 */
public final class StartupConfigLoader {

  /** Environment variable name for the config file path, where the file is loaded from. */
  public static final String ENV_CONFIG_FILE = "MACSTAB_CHAOS_CONFIG_FILE";

  /**
   * Environment variable name for the inline JSON config; the value is parsed directly as JSON
   * text.
   */
  public static final String ENV_CONFIG_JSON = "MACSTAB_CHAOS_CONFIG_JSON";

  /**
   * Environment variable name for the base64-encoded JSON config; the value is decoded from base64
   * and then parsed as JSON text.
   */
  public static final String ENV_CONFIG_BASE64 = "MACSTAB_CHAOS_CONFIG_BASE64";

  /**
   * Environment variable name for the debug dump flag ({@code true|1|yes|on}, case-insensitive),
   * read when neither the agent arg {@code debugDumpOnStart} nor the system property {@value
   * #PROPERTY_DEBUG_DUMP} is set; when on, a diagnostic dump of the loaded plan is printed at
   * startup. The agent arg {@code debugDump} turns it on as well.
   */
  public static final String ENV_DEBUG_DUMP = "MACSTAB_CHAOS_DEBUG_DUMP_ON_START";

  /** System property for the config file path; read after the agent arg. */
  public static final String PROPERTY_CONFIG_FILE = "macstab.chaos.configFile";

  /** System property for the inline JSON config; read after the agent arg. */
  public static final String PROPERTY_CONFIG_JSON = "macstab.chaos.configJson";

  /** System property for the base64-encoded JSON config; read after the agent arg. */
  public static final String PROPERTY_CONFIG_BASE64 = "macstab.chaos.configBase64";

  /** System property for the debug dump flag; read after the agent arg. */
  public static final String PROPERTY_DEBUG_DUMP = "macstab.chaos.debugDumpOnStart";

  /** Maximum config file size: 1 MiB. Prevents OOM from oversized files. */
  private static final long MAX_FILE_SIZE = ChaosPlanMapper.MAX_JSON_LENGTH;

  private static final String SOURCE_FILE_PREFIX = "file:";
  private static final String SOURCE_INLINE_JSON = "inline-json";
  private static final String SOURCE_BASE64 = "base64";

  /** Agent arg key carrying an inline JSON chaos plan. */
  private static final String ARG_CONFIG_JSON = "configJson";

  /** Agent arg key carrying a base64-encoded JSON chaos plan. */
  private static final String ARG_CONFIG_BASE64 = "configBase64";

  /** Agent arg key carrying a filesystem path to a JSON chaos plan. */
  private static final String ARG_CONFIG_FILE = "configFile";

  /** Agent arg key for opting into the startup-time plan dump. */
  private static final String ARG_DEBUG_DUMP_ON_START = "debugDumpOnStart";

  /** Alias of {@link #ARG_DEBUG_DUMP_ON_START}. */
  private static final String ARG_DEBUG_DUMP_ALIAS = "debugDump";

  /** POSIX sticky-bit flag (01000) within the Unix mode word. */
  private static final int POSIX_STICKY_BIT = 01000;

  /** {@code java.nio.file} attribute-view name for POSIX filesystems. */
  private static final String ATTR_VIEW_POSIX = "posix";

  /** {@code java.nio.file} attribute name exposing the full Unix mode (sticky bit in high bits). */
  private static final String ATTR_UNIX_MODE = "unix:mode";

  /** Appended to a world-writable refusal: mounts that report every file as 0777 hit it. */
  private static final String WORLD_WRITABLE_HINT =
      "; a volume that reports every file as 0777 (Azure Files, WSL /mnt, Docker Desktop bind"
          + " mounts) needs restrictive mount modes (file_mode/dir_mode, umask), or pass the plan"
          + " inline with configJson or configBase64";

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
      final @Nullable String rawAgentArgs, final Map<String, String> environment) {
    return load(AgentSettings.of(rawAgentArgs, environment));
  }

  /**
   * Loads a chaos plan from the first available configuration source, from settings already
   * resolved.
   *
   * @param settings the agent's settings
   * @return loaded plan with source metadata, or empty if no config is present
   * @throws ConfigLoadException if a source is specified but cannot be read or parsed
   */
  @Internal
  public static Optional<LoadedPlan> load(final AgentSettings settings) {
    final String inlineJson =
        settings.nonBlankValue(ARG_CONFIG_JSON, PROPERTY_CONFIG_JSON, ENV_CONFIG_JSON);
    final String base64Json =
        settings.nonBlankValue(ARG_CONFIG_BASE64, PROPERTY_CONFIG_BASE64, ENV_CONFIG_BASE64);
    final String file =
        settings.nonBlankValue(ARG_CONFIG_FILE, PROPERTY_CONFIG_FILE, ENV_CONFIG_FILE);

    // "debugDump" is the spelling used in parts of the documentation; both keys are accepted.
    final String debugDump =
        settings.value(ARG_DEBUG_DUMP_ON_START, PROPERTY_DEBUG_DUMP, ENV_DEBUG_DUMP);
    final boolean debugDumpOnStart =
        AgentArgs.parseBoolean(ARG_DEBUG_DUMP_ON_START, debugDump, false)
            || settings.args().getBoolean(ARG_DEBUG_DUMP_ALIAS, false);

    if (inlineJson != null) {
      final ChaosPlan plan = ChaosPlanMapper.read(inlineJson, SOURCE_INLINE_JSON);
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

  /**
   * Decodes standard or URL-safe base64, ignoring whitespace (line breaks from wrapped {@code
   * base64} output). Any other character is an error rather than skipped, so malformed input is
   * reported as base64, not as a misleading JSON parse error.
   */
  private static byte[] decodeBase64(final String encoded) {
    final String compact = encoded.replaceAll("\\s+", "");
    try {
      return Base64.getDecoder().decode(compact);
    } catch (final IllegalArgumentException standardFailure) {
      try {
        return Base64.getUrlDecoder().decode(compact);
      } catch (final IllegalArgumentException urlFailure) {
        urlFailure.addSuppressed(standardFailure);
        throw new ConfigLoadException(
            "invalid base64 encoding in chaos plan configuration", SOURCE_BASE64, urlFailure);
      }
    }
  }

  private static LoadedPlan loadFromBase64(
      final String base64Json, final boolean debugDumpOnStart) {
    final byte[] decoded = decodeBase64(base64Json);
    final String json = new String(decoded, StandardCharsets.UTF_8);
    final ChaosPlan plan = ChaosPlanMapper.read(json, SOURCE_BASE64);
    return new LoadedPlan(plan, SOURCE_BASE64, debugDumpOnStart, null);
  }

  private static LoadedPlan loadFromFile(final String filePath, final boolean debugDumpOnStart) {
    // One read for both the plan and the reloader's baseline: a second read could see a newer
    // file, which the reloader would then treat as already applied.
    final PlanFile file =
        readPlanFile(filePath)
            .orElseThrow(
                () ->
                    new ConfigLoadException(
                        "config file does not exist: " + filePath, SOURCE_FILE_PREFIX + filePath));
    final ChaosPlan plan = ChaosPlanMapper.read(file.json(), SOURCE_FILE_PREFIX + filePath);
    // The configured path, not the resolved target: a reloading poller must keep watching the
    // ConfigMap link, whose target directory is replaced on every update.
    return new LoadedPlan(
        plan,
        SOURCE_FILE_PREFIX + filePath,
        debugDumpOnStart,
        Path.of(filePath).toAbsolutePath().normalize(),
        file);
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
    return readValidatedPlanFile(filePath);
  }

  private static ChaosPlan readValidatedPlanFile(final String filePath) {
    final Path target = validateAndResolvePath(filePath);
    return ChaosPlanMapper.read(readFileContents(target, filePath), SOURCE_FILE_PREFIX + filePath);
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
      // Read at most one byte past the limit: the size check above ran on a stat, and a file that
      // grows before the read must not be loaded into memory in full.
      final byte[] content = inputStream.readNBytes((int) MAX_FILE_SIZE + 1);
      if (content.length > MAX_FILE_SIZE) {
        throw new ConfigLoadException(
            "chaos plan config file exceeds maximum size of " + MAX_FILE_SIZE + " bytes: " + path,
            SOURCE_FILE_PREFIX + originalFilePath);
      }
      return new String(content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new ConfigLoadException(
          "failed to read chaos plan config file: " + path,
          SOURCE_FILE_PREFIX + originalFilePath,
          exception);
    }
  }

  /**
   * Rejects a plan file (or ConfigMap link, or the link to its directory) owned by another user
   * than the JVM's, root excepted: in a shared world-writable directory such as {@code /tmp},
   * another local user could otherwise plant the plan while it is missing and inject their faults.
   * Applied only on file systems with a {@code posix} view (Windows' domain-qualified owner names
   * do not compare with {@code user.name}); skipped where the owner is not available or the user
   * name is unknown (containers running an unnamed uid).
   */
  private static void rejectForeignOwner(
      final Path path,
      final String filePath,
      final String subject,
      final Function<Path, String> ownerOf,
      final String self) {
    if (self.isBlank() || self.equals("?")) {
      return;
    }
    final String owner;
    try {
      owner = ownerOf.apply(path);
    } catch (final UncheckedIOException | UnsupportedOperationException unavailable) {
      return;
    }
    if (!owner.equals(self) && !owner.equals("root")) {
      throw new ConfigLoadException(
          subject
              + " "
              + path
              + " is owned by "
              + owner
              + ", not by the JVM's user "
              + self
              + " or root",
          SOURCE_FILE_PREFIX + filePath);
    }
  }

  /**
   * Validates the file path against basic safety constraints.
   *
   * <p>{@link Path#normalize()} resolves all {@code ..} and {@code .} path components before any
   * check is applied, neutralising traversal sequences. Symlink rejection is the primary
   * path-safety control on the resolved path; it covers the file itself, while a link to the file's
   * directory is followed (its mode is checked on the real directory, its owner as the file's).
   *
   * @throws ConfigLoadException if the path is unsafe, a symlink other than the Kubernetes
   *     ConfigMap layout, or exceeds size limits
   */
  private static Path validateAndResolvePath(final String filePath) {
    return validateAndResolvePath(
        filePath,
        Path.of(filePath).getFileSystem().supportedFileAttributeViews(),
        StartupConfigLoader::ownerName,
        System.getProperty("user.name", ""));
  }

  /**
   * Test seam of {@link #validateAndResolvePath(String)}: the file system's attribute views, the
   * owner lookup and the JVM's user name are passed in, so a test can model a file system without a
   * {@code posix} view and files owned by different users.
   *
   * @param filePath the configured path
   * @param supportedViews the attribute views of the path's file system
   * @param ownerOf the owner name of a path, not following a link in its last component; throws
   *     {@link UncheckedIOException} or {@link UnsupportedOperationException} where the owner is
   *     unavailable
   * @param jvmUser the JVM's user name ({@code user.name})
   * @return the regular file to read
   */
  static Path validateAndResolvePath(
      final String filePath,
      final Set<String> supportedViews,
      final Function<Path, String> ownerOf,
      final String jvmUser) {
    final Path path = Path.of(filePath).toAbsolutePath().normalize();

    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConfigLoadException(
          "config file does not exist: " + path, SOURCE_FILE_PREFIX + filePath);
    }
    final Path target = Files.isSymbolicLink(path) ? configMapTarget(path, filePath) : path;
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConfigLoadException(
          "config path is not a regular file: " + target, SOURCE_FILE_PREFIX + filePath);
    }
    rejectIfOversize(target, filePath);
    // Mode bits and owner names are POSIX notions: a file system without a posix view (Windows)
    // expresses the same intent through ACLs, and its owner names never equal user.name.
    if (supportedViews.contains(ATTR_VIEW_POSIX)) {
      rejectWorldWritable(target, filePath);
      rejectForeignOwner(path, filePath, "config file", ownerOf, jvmUser);
      if (!target.equals(path)) {
        rejectForeignOwner(target, filePath, "config file", ownerOf, jvmUser);
      }
      // The mode check follows a link to the directory, so whoever owns the link chooses the
      // directory the plan is read from: the link must be trusted as the file is.
      final Path parent = target.getParent();
      if (parent != null && Files.isSymbolicLink(parent)) {
        rejectForeignOwner(parent, filePath, "config file's directory link", ownerOf, jvmUser);
      }
    }
    return target;
  }

  private static String ownerName(final Path path) {
    try {
      return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
    } catch (final IOException unavailable) {
      throw new UncheckedIOException(unavailable);
    }
  }

  /**
   * Resolves a symbolic link in the layout Kubernetes uses for ConfigMap and Secret volumes, and
   * rejects every other link. In that layout {@code <dir>/plan.json} links to {@code
   * ..data/plan.json}, {@code <dir>/..data} links to a {@code ..<timestamp>} directory beside it,
   * and an update swaps {@code ..data} atomically. The returned path is the real file inside the
   * timestamped directory; the caller applies the regular-file, size and permission checks to it
   * and reads it without following links, so a link planted anywhere else is still refused.
   */
  private static Path configMapTarget(final Path link, final String originalPath) {
    try {
      final Path linkTarget = Files.readSymbolicLink(link);
      final Path fileName = link.getFileName();
      if (linkTarget.isAbsolute()
          || linkTarget.getNameCount() != 2
          || !linkTarget.getName(0).toString().equals("..data")
          || !linkTarget.getName(1).equals(fileName)) {
        throw new ConfigLoadException(
            "config file is a symbolic link (rejected for safety; only the Kubernetes ConfigMap"
                + " layout <dir>/<name> -> ..data/<name> is followed): "
                + link,
            SOURCE_FILE_PREFIX + originalPath);
      }
      // Absolute (see validateAndResolvePath), so a link always has a parent directory.
      final Path directory =
          java.util.Objects.requireNonNull(link.getParent(), "parent of an absolute link");
      final Path data = directory.resolve("..data");
      if (!Files.isSymbolicLink(data)) {
        throw new ConfigLoadException(
            "config file links into ..data, which is not the Kubernetes data link: " + data,
            SOURCE_FILE_PREFIX + originalPath);
      }
      final Path version = Files.readSymbolicLink(data);
      if (version.isAbsolute()
          || version.getNameCount() != 1
          || !version.getFileName().toString().startsWith("..")) {
        throw new ConfigLoadException(
            "..data does not point at a timestamped directory beside it: " + data,
            SOURCE_FILE_PREFIX + originalPath);
      }
      return directory.resolve(version).resolve(fileName);
    } catch (final IOException unreadable) {
      throw new ConfigLoadException(
          "cannot resolve config file link: " + link,
          SOURCE_FILE_PREFIX + originalPath,
          unreadable);
    }
  }

  /**
   * The file a configured plan path reads from, with what identifies its contents for change
   * detection.
   *
   * @param target the regular file read (inside the ConfigMap's timestamped directory, if any)
   * @param json the file's contents
   * @param lastModified the file's modification time
   * @param size the file's size in bytes
   * @param fileKey the file system's key of the file (inode), or {@code null}
   */
  @Internal
  public record PlanFile(
      Path target,
      String json,
      java.nio.file.attribute.FileTime lastModified,
      long size,
      @Nullable Object fileKey) {}

  /**
   * Reads a plan file with the startup checks (regular file or Kubernetes ConfigMap link, size cap,
   * permissions), for a poller that reloads it.
   *
   * @param filePath the configured path
   * @return the file and its contents, or empty when the configured path does not exist
   * @throws ConfigLoadException if the path is unsafe or unreadable
   */
  @Internal
  public static Optional<PlanFile> readPlanFile(final String filePath) {
    final Path path = Path.of(filePath).toAbsolutePath().normalize();
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    final Path target = validateAndResolvePath(filePath);
    try {
      final java.nio.file.attribute.BasicFileAttributes attributes =
          Files.readAttributes(
              target, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      final String json = readFileContents(target, filePath);
      return Optional.of(
          new PlanFile(
              target,
              json,
              attributes.lastModifiedTime(),
              attributes.size(),
              attributes.fileKey()));
    } catch (final IOException unreadable) {
      throw new ConfigLoadException(
          "failed to read chaos plan config file: " + target,
          SOURCE_FILE_PREFIX + filePath,
          unreadable);
    }
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
   * is world-writable without the sticky bit. Either condition lets any local user on the host swap
   * the plan contents for an attacker-chosen payload between deploys — activating chaos with
   * scenarios the operator never authorised. The directory is checked where its links lead: Linux
   * reports every symbolic link as 0777. Called only for a path whose file system has a {@code
   * posix} view; a mount that reports every file as 0777 is refused, with a hint.
   */
  private static void rejectWorldWritable(final Path path, final String originalPath) {
    try {
      final Set<PosixFilePermission> filePerms =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      if (filePerms.contains(PosixFilePermission.OTHERS_WRITE)) {
        throw new ConfigLoadException(
            "config file is world-writable (rejected for safety): " + path + WORLD_WRITABLE_HINT,
            SOURCE_FILE_PREFIX + originalPath);
      }
      final Path linkedParent = path.getParent();
      if (linkedParent != null) {
        final Path parent = linkedParent.toRealPath();
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
                  + parent
                  + WORLD_WRITABLE_HINT,
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

  /**
   * Result of successfully loading a chaos plan from a configuration source.
   *
   * @param plan the parsed chaos plan
   * @param source human-readable source identifier (e.g. {@code "file:/etc/chaos/plan.json"})
   * @param debugDumpOnStart whether to print a diagnostic dump at startup
   * @param filePath the resolved file path when the source was a config file, {@code null}
   *     otherwise; used by the bootstrap poller to set up file watching
   * @param planFile the file read the plan came from, {@code null} for other sources: the
   *     reloader's baseline
   */
  public record LoadedPlan(
      ChaosPlan plan,
      String source,
      boolean debugDumpOnStart,
      @Nullable Path filePath,
      @Internal @Nullable PlanFile planFile) {

    /** The canonical constructor, with the reloader's file read: internal to the agent. */
    @Internal
    public LoadedPlan {}

    /**
     * Creates a loaded plan without a file read.
     *
     * @param plan the parsed plan
     * @param source where it came from
     * @param debugDumpOnStart whether to print a diagnostic dump at startup
     * @param filePath the config file path, or {@code null}
     */
    public LoadedPlan(
        final ChaosPlan plan,
        final String source,
        final boolean debugDumpOnStart,
        final @Nullable Path filePath) {
      this(plan, source, debugDumpOnStart, filePath, null);
    }
  }
}
