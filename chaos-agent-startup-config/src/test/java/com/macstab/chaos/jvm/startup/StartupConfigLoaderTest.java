package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StartupConfigLoader")
class StartupConfigLoaderTest {

  @TempDir Path tempDir;

  @Nested
  @DisplayName("source resolution")
  class SourceResolution {

    @Test
    @DisplayName("returns empty when no config source is provided")
    void noConfigReturnsEmpty() {
      Optional<StartupConfigLoader.LoadedPlan> result = StartupConfigLoader.load(null, Map.of());
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("inline JSON from agent args is loaded")
    void inlineJsonFromArgs() {
      String json = writeSampleJson("inline-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("inline-test");
      assertThat(loaded.source()).isEqualTo("inline-json");
    }

    @Test
    @DisplayName("each source has a system property, read after the agent arg")
    void systemProperties() {
      final String json = writeSampleJson("property-test");
      System.setProperty("macstab.chaos.configJson", json);
      System.setProperty("macstab.chaos.debugDumpOnStart", "true");
      try {
        final StartupConfigLoader.LoadedPlan loaded =
            StartupConfigLoader.load(null, Map.of()).orElseThrow();

        assertThat(loaded.plan().metadata().name()).isEqualTo("property-test");
        assertThat(loaded.debugDumpOnStart()).isTrue();
        assertThat(
                StartupConfigLoader.load("configJson=" + writeSampleJson("arg-test"), Map.of())
                    .orElseThrow()
                    .plan()
                    .metadata()
                    .name())
            .isEqualTo("arg-test");
      } finally {
        System.clearProperty("macstab.chaos.configJson");
        System.clearProperty("macstab.chaos.debugDumpOnStart");
      }
    }

    @Test
    @DisplayName("inline JSON from environment is loaded")
    void inlineJsonFromEnv() {
      String json = writeSampleJson("env-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(null, Map.of(StartupConfigLoader.ENV_CONFIG_JSON, json))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("env-test");
    }

    @Test
    @DisplayName("base64-encoded JSON from agent args is loaded")
    void base64FromArgs() {
      String json = writeSampleJson("b64-test");
      String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configBase64=" + encoded, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("b64-test");
      assertThat(loaded.source()).isEqualTo("base64");
    }

    @Test
    @DisplayName("URL-safe and line-wrapped base64 are accepted")
    void base64VariantsAreAccepted() {
      final byte[] json = writeSampleJson("b64-variants").getBytes(StandardCharsets.UTF_8);
      final String urlSafe = Base64.getUrlEncoder().encodeToString(json);
      final String wrapped = Base64.getMimeEncoder().encodeToString(json);

      assertThat(
              StartupConfigLoader.load(null, Map.of(StartupConfigLoader.ENV_CONFIG_BASE64, urlSafe))
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("b64-variants");
      assertThat(
              StartupConfigLoader.load(null, Map.of(StartupConfigLoader.ENV_CONFIG_BASE64, wrapped))
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("b64-variants");
    }

    @Test
    @DisplayName("URL-safe base64 that the standard alphabet rejects is decoded")
    void urlSafeOnlyBase64IsDecoded() {
      // A run of '?' encodes to '/' in the standard alphabet, so the URL-safe form carries '_'.
      final String urlSafe =
          Base64.getUrlEncoder()
              .encodeToString(writeSampleJson("url-safe?????").getBytes(StandardCharsets.UTF_8));
      assertThat(urlSafe).contains("_");

      final StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configBase64=" + urlSafe, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("url-safe?????");
      assertThat(loaded.source()).isEqualTo("base64");
    }

    @Test
    @DisplayName("invalid base64 keeps both decoders' failures for diagnosis")
    void invalidBase64KeepsBothFailures() {
      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.load("configBase64=*#!", Map.of()))
          .satisfies(
              failure -> {
                assertThat(failure.getSource()).isEqualTo("base64");
                assertThat(failure.getCause()).isInstanceOf(IllegalArgumentException.class);
                assertThat(failure.getCause().getSuppressed())
                    .singleElement()
                    .isInstanceOf(IllegalArgumentException.class);
              });
    }

    @Test
    @DisplayName("base64 with invalid characters is rejected as such, not as broken JSON")
    void invalidBase64IsRejected() {
      final String corrupted =
          Base64.getEncoder().encodeToString(writeSampleJson("x").getBytes(StandardCharsets.UTF_8))
              + "*#!";

      assertThatThrownBy(
              () ->
                  StartupConfigLoader.load(
                      null, Map.of(StartupConfigLoader.ENV_CONFIG_BASE64, corrupted)))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid base64");
    }

    @Test
    @DisplayName("MACSTAB_CHAOS_DEBUG_DUMP_ON_START accepts the same values as the agent arg")
    void debugDumpEnvAcceptsArgSpellings() {
      final String json = writeSampleJson("dump");
      assertThat(
              StartupConfigLoader.load(
                      "configJson=" + json, Map.of(StartupConfigLoader.ENV_DEBUG_DUMP, "yes"))
                  .orElseThrow()
                  .debugDumpOnStart())
          .isTrue();
    }

    @Test
    @DisplayName("file path from agent args is loaded")
    void fileFromArgs() throws Exception {
      Path configFile = tempDir.resolve("plan.json");
      Files.writeString(configFile, writeSampleJson("file-test"));

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configFile=" + configFile, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("file-test");
      assertThat(loaded.source()).startsWith("file:");
    }

    @Test
    @DisplayName("a relative file path is reported absolute, also when it fails")
    void relativePathReportedAbsolute() throws Exception {
      final Path configFile = tempDir.resolve("relative.json");
      Files.writeString(configFile, writeSampleJson("relative-test"));
      final Path workingDirectory = Path.of("").toAbsolutePath();
      assumeTrue(configFile.getRoot().equals(workingDirectory.getRoot()));
      final Path relative = workingDirectory.relativize(configFile);
      final Path missing = workingDirectory.relativize(tempDir.resolve("missing.json"));

      final StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configFile=" + relative, Map.of()).orElseThrow();

      assertThat(loaded.source()).isEqualTo("file:" + configFile.toAbsolutePath().normalize());
      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.load("configFile=" + missing, Map.of()))
          .satisfies(
              failure ->
                  assertThat(failure.getSource())
                      .isEqualTo("file:" + tempDir.resolve("missing.json").toAbsolutePath()));
    }

    @Test
    @DisplayName("agent args take precedence over environment variables")
    void argsPrecedence() throws Exception {
      Path envFile = tempDir.resolve("env-plan.json");
      Files.writeString(envFile, writeSampleJson("env-plan"));

      String argsJson = writeSampleJson("args-plan");

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + argsJson,
                  Map.of(StartupConfigLoader.ENV_CONFIG_FILE, envFile.toString()))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("args-plan");
    }

    @Test
    @DisplayName("inline JSON takes precedence over base64 and file")
    void inlineBeatsBase64AndFile() throws Exception {
      String inlineJson = writeSampleJson("inline-wins");
      String base64 =
          Base64.getEncoder()
              .encodeToString(writeSampleJson("b64-loses").getBytes(StandardCharsets.UTF_8));
      Path file = tempDir.resolve("file-loses.json");
      Files.writeString(file, writeSampleJson("file-loses"));

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + inlineJson + ";configBase64=" + base64,
                  Map.of(StartupConfigLoader.ENV_CONFIG_FILE, file.toString()))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("inline-wins");
    }
  }

  @Nested
  @DisplayName("debug dump flag")
  class DebugDump {

    @Test
    @DisplayName("debugDumpOnStart from agent args")
    void debugFromArgs() {
      String json = writeSampleJson("debug-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json + ";debugDumpOnStart=true", Map.of())
              .orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isTrue();
    }

    @Test
    @DisplayName("debugDumpOnStart from environment")
    void debugFromEnv() {
      String json = writeSampleJson("debug-env");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + json, Map.of(StartupConfigLoader.ENV_DEBUG_DUMP, "true"))
              .orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isTrue();
    }

    @Test
    @DisplayName("debug dump defaults to false")
    void debugDefaultFalse() {
      String json = writeSampleJson("no-debug");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json, Map.of()).orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isFalse();
    }
  }

  @Nested
  @DisplayName("error paths")
  class ErrorPaths {

    @Test
    @DisplayName("non-existent file throws ConfigLoadException")
    void missingFile() {
      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.load("configFile=/does/not/exist.json", Map.of()))
          .withMessageContaining("does not exist")
          .satisfies(
              failure -> assertThat(failure.getSource()).isEqualTo("file:/does/not/exist.json"));
    }

    @Test
    @DisplayName("invalid base64 throws ConfigLoadException")
    void invalidBase64() {
      assertThatThrownBy(() -> StartupConfigLoader.load("configBase64=!!!not-base64!!!", Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid base64");
    }

    @Test
    @DisplayName("invalid JSON in file throws ConfigLoadException")
    void invalidJsonInFile() throws Exception {
      Path bad = tempDir.resolve("bad.json");
      Files.writeString(bad, "{not valid}");

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + bad, Map.of()))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("directory path is rejected")
    void directoryPathRejected() {
      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + tempDir, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("not a regular file");
    }
  }

  @Nested
  @DisplayName("file path security")
  class FilePathSecurity {

    @Test
    @DisplayName("traversal path normalized to non-existent file throws does-not-exist")
    void traversalPathNormalizedToNonExistent() {
      // normalize() resolves '..' before any check; the resolved path is validated normally.
      // This path normalizes to a non-existent absolute path, triggering the existence guard.
      assertThatThrownBy(
              () ->
                  StartupConfigLoader.load(
                      "configFile=/no/such/path/../../../../../no/such/plan.json", Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("symlink is rejected")
    void symlinkRejected() throws Exception {
      Path real = tempDir.resolve("real.json");
      Files.writeString(real, writeSampleJson("real"));
      Path link = tempDir.resolve("link.json");
      Files.createSymbolicLink(link, real);

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + link, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("symbolic link");
    }

    @Test
    @DisplayName("oversized file is rejected")
    void oversizedFileRejected() throws Exception {
      Path huge = tempDir.resolve("huge.json");
      byte[] data = new byte[1_048_577];
      java.util.Arrays.fill(data, (byte) ' ');
      Files.write(huge, data);

      // The size is checked on a stat before the read, so the message carries the actual size.
      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + huge, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("exceeds maximum size")
          .hasMessageContaining("(1048577 actual)");
    }

    @Test
    @DisplayName("a file of exactly 1 MiB is still accepted")
    void fileAtSizeLimitAccepted() throws Exception {
      final String json = writeSampleJson("at-limit");
      final Path atLimit = tempDir.resolve("at-limit.json");
      Files.writeString(atLimit, json + " ".repeat(1_048_576 - json.length()));
      assertThat(Files.size(atLimit)).isEqualTo(1_048_576L);

      assertThat(
              StartupConfigLoader.load("configFile=" + atLimit, Map.of())
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("at-limit");
    }

    @Test
    @DisplayName("world-writable file is rejected")
    void worldWritableFileRejected() throws Exception {
      assumePosix();
      final Path file = tempDir.resolve("open.json");
      Files.writeString(file, writeSampleJson("open"));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + file, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("config file is world-writable");
    }

    @Test
    @DisplayName("file in a world-writable directory without sticky bit is rejected")
    void worldWritableParentRejected() throws Exception {
      assumePosix();
      final Path directory = Files.createDirectory(tempDir.resolve("shared"));
      final Path file = directory.resolve("plan.json");
      Files.writeString(file, writeSampleJson("shared"));
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + file, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("parent directory is world-writable without sticky bit");
    }

    @Test
    @DisplayName("file in a world-writable sticky directory (like /tmp) is accepted")
    void worldWritableStickyParentAccepted() throws Exception {
      assumePosix();
      final Path directory = Files.createDirectory(tempDir.resolve("sticky"));
      final Path file = directory.resolve("plan.json");
      Files.writeString(file, writeSampleJson("sticky"));
      // java.nio cannot set the sticky bit; chmod can.
      final Process chmod =
          new ProcessBuilder("chmod", "1777", directory.toString()).inheritIO().start();
      assumeTrue(chmod.waitFor() == 0, "chmod 1777 not available");

      assertThat(
              StartupConfigLoader.load("configFile=" + file, Map.of())
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("sticky");
    }
  }

  @Nested
  @DisplayName("plan file stamps")
  class PlanFileStamps {

    /** A ConfigMap volume: plan.json -> ..data/plan.json, ..data -> ..ts. */
    private Path configMap(final String json) throws Exception {
      final Path volume = Files.createDirectories(tempDir.resolve("configmap"));
      final Path version = Files.createDirectories(volume.resolve("..2026_10_30_12_00_00.1"));
      Files.writeString(version.resolve("plan.json"), json);
      Files.createSymbolicLink(volume.resolve("..data"), version.getFileName());
      return Files.createSymbolicLink(volume.resolve("plan.json"), Path.of("..data", "plan.json"));
    }

    @Test
    @DisplayName("the stamp of a file is the one its read records")
    void stampMatchesRead() throws Exception {
      final Path file = Files.writeString(tempDir.resolve("plan.json"), writeSampleJson("p"));

      final StartupConfigLoader.PlanFileStamp stamp =
          StartupConfigLoader.statPlanFile(file.toString()).orElseThrow();
      final StartupConfigLoader.PlanFile read =
          StartupConfigLoader.readPlanFile(file.toString()).orElseThrow();

      assertThat(stamp)
          .isEqualTo(
              new StartupConfigLoader.PlanFileStamp(
                  read.target(), read.lastModified(), read.size(), read.fileKey()));
    }

    @Test
    @DisplayName("a missing file has no stamp and no read")
    void missingFile() {
      final String missing = tempDir.resolve("absent.json").toString();

      assertThat(StartupConfigLoader.statPlanFile(missing)).isEmpty();
      assertThat(StartupConfigLoader.readPlanFile(missing)).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a ConfigMap stamp names the file inside the timestamped directory")
    void configMapStamp() throws Exception {
      final Path link = configMap(writeSampleJson("p"));

      assertThat(StartupConfigLoader.statPlanFile(link.toString()).orElseThrow().target())
          .isEqualTo(
              link.getParent()
                  .toAbsolutePath()
                  .normalize()
                  .resolve("..2026_10_30_12_00_00.1")
                  .resolve("plan.json"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a ..data link to a removed directory is a missing file")
    void danglingConfigMap() throws Exception {
      final Path link = configMap(writeSampleJson("p"));
      final Path version = link.getParent().resolve("..2026_10_30_12_00_00.1");
      Files.delete(version.resolve("plan.json"));
      Files.delete(version);

      assertThat(StartupConfigLoader.statPlanFile(link.toString())).isEmpty();
      assertThat(StartupConfigLoader.readPlanFile(link.toString())).isEmpty();
      assertThatThrownBy(() -> StartupConfigLoader.loadPlanFromFile(link.toString()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("a file removed between its checks and its read is a missing file")
    void removedBeforeTheRead() throws Exception {
      final Path file = Files.writeString(tempDir.resolve("plan.json"), writeSampleJson("p"));

      final Optional<StartupConfigLoader.PlanFile> read =
          StartupConfigLoader.readPlanFile(
              file.toString(),
              Set.of("posix"),
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (final java.io.IOException failure) {
                  throw new java.io.UncheckedIOException(failure);
                }
                return "me";
              },
              "me");

      assertThat(read).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a link outside the ConfigMap layout is refused by the stamp too")
    void otherLinkRefused() throws Exception {
      final Path real = Files.writeString(tempDir.resolve("real.json"), writeSampleJson("p"));
      final Path link = Files.createSymbolicLink(tempDir.resolve("plan.json"), real);

      assertThatThrownBy(() -> StartupConfigLoader.statPlanFile(link.toString()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("symbolic link");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a stamp whose attributes cannot be read is a load failure")
    void unreadableAttributes() throws Exception {
      assumeFalse("root".equals(System.getProperty("user.name")), "root traverses mode 000");
      final Path directory = Files.createDirectories(tempDir.resolve("closed"));
      final Path file = Files.writeString(directory.resolve("plan.json"), writeSampleJson("p"));
      Files.setPosixFilePermissions(directory, Set.of());
      try {
        assertThatThrownBy(() -> StartupConfigLoader.statPlanFile(file.toString()))
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageContaining("failed to read the attributes");
      } finally {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
      }
    }
  }

  @Nested
  @DisplayName("file ownership")
  class FileOwnership {

    @Test
    @DisplayName("file owned by another user than the JVM's is rejected")
    void foreignOwnerRejected() throws Exception {
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("foreign"));
      assumeTrue(!"root".equals(Files.getOwner(file).getName()), "test runs as root");

      withUserName(
          "someone-else",
          () ->
              assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + file, Map.of()))
                  .isInstanceOf(ConfigLoadException.class)
                  .hasMessageContaining("not by the JVM's user someone-else or root"));
    }

    @Test
    @DisplayName("ownership check is skipped when the JVM's user name is unknown or blank")
    void unknownUserSkipsOwnershipCheck() throws Exception {
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("unnamed-uid"));

      // Containers running an unnamed uid report "?" (or nothing) as user.name.
      for (final String unnamed : List.of("?", "")) {
        withUserName(
            unnamed,
            () ->
                assertThat(
                        StartupConfigLoader.load("configFile=" + file, Map.of())
                            .orElseThrow()
                            .plan()
                            .metadata()
                            .name())
                    .isEqualTo("unnamed-uid"));
      }
    }

    private void withUserName(final String userName, final Runnable action) {
      final String original = System.getProperty("user.name");
      System.setProperty("user.name", userName);
      try {
        action.run();
      } finally {
        System.setProperty("user.name", original);
      }
    }
  }

  @Nested
  @DisplayName("trust checks across platforms")
  class PlatformTrust {

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a plan in a directory reached through a symbolic link loads")
    void planBehindSymlinkedDirectoryLoads() throws Exception {
      final Path real = Files.createDirectory(tempDir.resolve("real"));
      Files.writeString(real.resolve("plan.json"), writeSampleJson("linked-dir"));
      final Path linked = Files.createSymbolicLink(tempDir.resolve("linked"), real);
      if (OS.MAC.isCurrentOs()) {
        // Linux reports every symbolic link as 0777; macOS gives a link its own mode, so set it.
        final Process chmod =
            new ProcessBuilder("chmod", "-h", "777", linked.toString()).inheritIO().start();
        assumeTrue(chmod.waitFor() == 0, "chmod -h not available");
      }

      assertThat(
              StartupConfigLoader.load("configFile=" + linked.resolve("plan.json"), Map.of())
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("linked-dir");
    }

    @Test
    @DisplayName("a file system without a posix view skips the owner check (Windows DOMAIN\\user)")
    void ownerCheckSkippedWithoutPosixView() throws Exception {
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("windows"));

      final Path target =
          StartupConfigLoader.validateAndResolvePath(
              file.toString(),
              Set.of("basic", "owner", "dos", "acl", "user"),
              path -> "DESKTOP\\alice",
              "alice");

      assertThat(target).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("a config file whose owner cannot be read is not rejected for its owner")
    void unreadableOwnerIsNotCompared() throws Exception {
      assumePosix();
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("owner-unreadable"));

      final Path target =
          StartupConfigLoader.validateAndResolvePath(
              file.toString(),
              Set.of("basic", "posix", "unix", "owner"),
              path -> {
                throw new java.io.UncheckedIOException(new java.io.IOException("no owner"));
              },
              "alice");

      assertThat(target).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("a directory link owned by another user than the JVM's is rejected")
    void foreignDirectoryLinkRejected() throws Exception {
      assumePosix();
      final Path real = Files.createDirectory(tempDir.resolve("real"));
      Files.writeString(real.resolve("plan.json"), writeSampleJson("planted"));
      final Path linked =
          Files.createSymbolicLink(tempDir.resolve("linked"), real).toAbsolutePath().normalize();

      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(
              () ->
                  StartupConfigLoader.validateAndResolvePath(
                      linked.resolve("plan.json").toString(),
                      FileSystems.getDefault().supportedFileAttributeViews(),
                      path -> path.equals(linked) ? "bob" : "alice",
                      "alice"))
          .withMessageContaining(linked + " is owned by bob");
    }

    @Test
    @DisplayName("a world-writable refusal says how to load a plan from a 0777 mount")
    void worldWritableRefusalNamesTheWayOut() throws Exception {
      assumePosix();
      final Path directory = Files.createDirectory(tempDir.resolve("mount"));
      final Path file = directory.resolve("plan.json");
      Files.writeString(file, writeSampleJson("mount"));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + file, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("config file is world-writable")
          .hasMessageContaining("configJson");

      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + file, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("parent directory is world-writable")
          .hasMessageContaining("configJson");
    }
  }

  @Nested
  @DisplayName("Kubernetes ConfigMap layout")
  class ConfigMapLayout {

    private static final String VERSION_DIR = "..2026_11_04_12_00_00.000000001";

    @Test
    @DisplayName("follows <dir>/plan.json -> ..data/plan.json -> ..<timestamp>/plan.json")
    void configMapLinkFollowed() throws Exception {
      final Path volume = volume(Path.of("..data", "plan.json"), Path.of(VERSION_DIR));
      final Path link = volume.resolve("plan.json");

      final StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configFile=" + link, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("config-map");
      // The poller watches the configured link; the read came from the versioned file.
      assertThat(loaded.source()).isEqualTo("file:" + link);
      assertThat(loaded.filePath()).isEqualTo(link.toAbsolutePath().normalize());
      assertThat(loaded.planFile()).isNotNull();
      assertThat(loaded.planFile().target())
          .isEqualTo(volume.resolve(VERSION_DIR).resolve("plan.json").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("rejects a link whose target is not ..data/<same name>")
    void linkOutsideLayoutRejected() throws Exception {
      for (final Path linkTarget :
          List.of(
              Path.of("..data", "other.json"),
              Path.of("data", "plan.json"),
              Path.of("..data", "nested", "plan.json"))) {
        final Path volume = volume(linkTarget, Path.of(VERSION_DIR));

        assertThatThrownBy(
                () ->
                    StartupConfigLoader.load("configFile=" + volume.resolve("plan.json"), Map.of()))
            .as("link to %s", linkTarget)
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageContaining("only the Kubernetes ConfigMap layout");
      }
    }

    @Test
    @DisplayName("rejects ..data when it is a plain directory, not the data link")
    void plainDataDirectoryRejected() throws Exception {
      final Path volume = Files.createDirectory(tempDir.resolve("plain"));
      final Path data = Files.createDirectory(volume.resolve("..data"));
      Files.writeString(data.resolve("plan.json"), writeSampleJson("plain"));
      Files.createSymbolicLink(volume.resolve("plan.json"), Path.of("..data", "plan.json"));

      assertThatThrownBy(
              () -> StartupConfigLoader.load("configFile=" + volume.resolve("plan.json"), Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("which is not the Kubernetes data link");
    }

    @Test
    @DisplayName("rejects ..data pointing anywhere but a timestamped directory beside it")
    void dataLinkOutsideLayoutRejected() throws Exception {
      final Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
      for (final Path dataTarget :
          List.of(
              elsewhere.toAbsolutePath(), Path.of(VERSION_DIR, "nested"), Path.of("v2026_11_04"))) {
        final Path volume = volume(Path.of("..data", "plan.json"), dataTarget);

        assertThatThrownBy(
                () ->
                    StartupConfigLoader.load("configFile=" + volume.resolve("plan.json"), Map.of()))
            .as("..data to %s", dataTarget)
            .isInstanceOf(ConfigLoadException.class)
            .hasMessageContaining("..data does not point at a timestamped directory");
      }
    }

    @Test
    @DisplayName("rejects a mount directory that is world-writable without the sticky bit")
    void worldWritableMountDirectoryRejected() throws Exception {
      assumePosix();
      assumeFalse("root".equals(System.getProperty("user.name")), "test runs as root");
      final Path volume = volume(Path.of("..data", "plan.json"), Path.of(VERSION_DIR));
      // Anyone who can write the mount directory can swap ..data and choose the plan read.
      Files.setPosixFilePermissions(volume, PosixFilePermissions.fromString("rwxrwxrwx"));

      assertThatThrownBy(
              () -> StartupConfigLoader.load("configFile=" + volume.resolve("plan.json"), Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("ConfigMap directory is world-writable without sticky bit")
          .hasMessageContaining(volume.toRealPath().toString());
    }

    /**
     * Builds a fresh volume directory: {@code plan.json} links to {@code linkTarget}, {@code
     * ..data} links to {@code dataTarget}, and the real plan lives in {@code VERSION_DIR}.
     */
    private Path volume(final Path linkTarget, final Path dataTarget) throws Exception {
      final Path volume = Files.createTempDirectory(tempDir, "volume");
      final Path version = Files.createDirectory(volume.resolve(VERSION_DIR));
      Files.writeString(version.resolve("plan.json"), writeSampleJson("config-map"));
      Files.createSymbolicLink(volume.resolve("..data"), dataTarget);
      Files.createSymbolicLink(volume.resolve("plan.json"), linkTarget);
      return volume;
    }
  }

  @Nested
  @DisplayName("loadPlanFromFile")
  class LoadPlanFromFile {

    @Test
    @DisplayName("reads and parses a plan file")
    void readsPlan() throws Exception {
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("integration"));

      assertThat(StartupConfigLoader.loadPlanFromFile(file.toString()).metadata().name())
          .isEqualTo("integration");
    }

    @Test
    @DisplayName("reads a file holding a single scenario, with a byte order mark")
    void readsSingleScenario() throws Exception {
      final Path file = tempDir.resolve("scenario.json");
      Files.writeString(
          file,
          "﻿{\"id\": \"from-file\", \"selector\": {\"type\": \"executor\", \"operations\":"
              + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": {\"type\": \"delay\", \"minDelay\":"
              + " \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}}");

      assertThat(StartupConfigLoader.loadPlanFromFile(file.toString()).scenarios())
          .extracting(com.macstab.chaos.jvm.api.ChaosScenario::id)
          .containsExactly("from-file");
    }

    @Test
    @DisplayName("applies the agent's file checks and names the file as the source")
    void appliesFileChecks() {
      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.loadPlanFromFile(tempDir.toString()))
          .withMessageContaining("not a regular file")
          .satisfies(failure -> assertThat(failure.getSource()).isEqualTo("file:" + tempDir));
    }
  }

  @Nested
  @DisplayName("source edge cases")
  class SourceEdgeCases {

    @Test
    @DisplayName("the debugDump spelling turns on the start-up dump")
    void debugDumpAlias() {
      final StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + writeSampleJson("alias") + ";debugDump=true", Map.of())
              .orElseThrow();

      assertThat(loaded.debugDumpOnStart()).isTrue();
    }

    @Test
    @DisplayName("a blank agent arg falls back to the environment; a blank variable is no source")
    void blankSources() {
      final String json = writeSampleJson("from-env");

      assertThat(
              StartupConfigLoader.load(
                      "configJson=  ", Map.of(StartupConfigLoader.ENV_CONFIG_JSON, json))
                  .orElseThrow()
                  .plan()
                  .metadata()
                  .name())
          .isEqualTo("from-env");
      assertThat(StartupConfigLoader.load(null, Map.of(StartupConfigLoader.ENV_CONFIG_JSON, "  ")))
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("file access failures")
  class FileAccessFailures {

    @Test
    @DisplayName("loadPlanFromFile reports a missing file")
    void missingFile() {
      final Path missing = tempDir.resolve("absent.json");

      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.loadPlanFromFile(missing.toString()))
          .withMessage("config file does not exist: " + missing)
          .satisfies(failure -> assertThat(failure.getSource()).isEqualTo("file:" + missing));
    }

    @Test
    @DisplayName("a file that passes the checks but cannot be opened is reported with the cause")
    void unreadableFile() throws Exception {
      assumePosix();
      final Path file = tempDir.resolve("plan.json");
      Files.writeString(file, writeSampleJson("unreadable"));
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
      assumeTrue(!Files.isReadable(file), "the user reads every file (root)");

      assertThatExceptionOfType(ConfigLoadException.class)
          .isThrownBy(() -> StartupConfigLoader.loadPlanFromFile(file.toString()))
          .withMessage("failed to read chaos plan config file: " + file)
          .withCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("a file owned by root passes the ownership check whoever runs the JVM")
    void rootOwnedFileAccepted() throws Exception {
      final Path hosts = Path.of("/etc/hosts");
      assumeTrue(
          Files.isRegularFile(hosts, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              && "root".equals(Files.getOwner(hosts).getName()),
          "needs a root-owned /etc/hosts");
      final String original = System.getProperty("user.name");
      System.setProperty("user.name", "someone-else");
      try {
        // Not a plan: the read gets past the ownership check and fails on the content instead.
        assertThatExceptionOfType(ConfigLoadException.class)
            .isThrownBy(() -> StartupConfigLoader.loadPlanFromFile(hosts.toString()))
            .withMessageStartingWith("failed to parse chaos plan JSON")
            .withMessageNotContaining("owned by");
      } finally {
        System.setProperty("user.name", original);
      }
    }
  }

  private static void assumePosix() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  private static String writeSampleJson(String planName) {
    ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata(planName, ""),
            null,
            List.of(
                ChaosScenario.builder("s1")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(10)))
                    .activationPolicy(ActivationPolicy.always())
                    .build()));
    return ChaosPlanMapper.write(plan);
  }
}
