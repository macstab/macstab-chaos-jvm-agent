package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sweep of bridge jars left by earlier JVMs: only the JVM user's own, on POSIX. */
@DisplayName("Bridge jar sweep")
class BridgeJarSweepTest {

  private static final FileTime TWO_DAYS_AGO =
      FileTime.from(Instant.now().minus(Duration.ofDays(2)));

  @TempDir Path tempDir;

  @Test
  @DisplayName("a stale bridge jar another user owns is not deleted")
  void foreignJarKept() throws Exception {
    assumePosix();
    final Path stale = staleJar(tempDir);
    final String owner = Files.getOwner(stale, LinkOption.NOFOLLOW_LINKS).getName();

    BridgeInjector.sweepStaleBridgeJars(tempDir, owner + "-someone-else");

    assertThat(stale).exists();
  }

  @Test
  @DisplayName("a stale bridge jar the JVM user owns is deleted")
  void ownJarDeleted() throws Exception {
    assumePosix();
    final Path stale = staleJar(tempDir);

    BridgeInjector.sweepStaleBridgeJars(
        tempDir, Files.getOwner(stale, LinkOption.NOFOLLOW_LINKS).getName());

    assertThat(stale).doesNotExist();
  }

  @Test
  @DisplayName("a bridgeDir that takes no file fails naming the directory and why")
  void bridgeDirThatTakesNoFileFails() throws Exception {
    final Path notADirectory = Files.writeString(tempDir.resolve("plain-file"), "x");

    assertThatThrownBy(() -> BridgeInjector.createBridgeJar(notADirectory))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageStartingWith("no directory takes the bridge jar: " + notADirectory + ": ")
        .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
  }

  @Test
  @DisplayName("a bridgeDir on a file system without a posix view takes the jar")
  void bridgeDirWithoutPosixView() throws Exception {
    final Path zip = tempDir.resolve("bridge.zip");
    try (FileSystem zipFs =
        FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
      final Path jar = BridgeInjector.createBridgeJar(zipFs.getPath("/"));

      assertThat(jar).exists();
      assertThat(jar.getFileName().toString()).startsWith("macstab-chaos-bootstrap-bridge");
    }
  }

  @Test
  @DisplayName("without a posix view the owner is not compared and a stale jar is deleted")
  void noPosixViewDeletes() throws Exception {
    final Path zip = tempDir.resolve("store.zip");
    try (FileSystem zipFs =
        FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
      final Path root = zipFs.getPath("/");
      assertThat(zipFs.supportedFileAttributeViews()).doesNotContain("posix");
      final Path stale = staleJar(root);

      BridgeInjector.sweepStaleBridgeJars(root, "someone-else");

      assertThat(stale).doesNotExist();
    }
  }

  private static Path staleJar(final Path directory) throws Exception {
    final Path jar =
        Files.writeString(directory.resolve("macstab-chaos-bootstrap-bridge-old.jar"), "x");
    Files.setLastModifiedTime(jar, TWO_DAYS_AGO);
    return jar;
  }

  private static void assumePosix() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }
}
