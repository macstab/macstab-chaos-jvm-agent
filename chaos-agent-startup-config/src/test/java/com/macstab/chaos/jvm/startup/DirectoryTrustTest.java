package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DirectoryTrust")
class DirectoryTrustTest {

  private static final Set<String> POSIX = Set.of("basic", "posix", "unix", "owner");

  @TempDir Path tempDir;

  @Test
  @DisplayName("an owner-only directory of the JVM's user is trusted")
  void ownDirectoryTrusted() throws Exception {
    assumePosix();
    final Path directory = Files.createDirectory(tempDir.resolve("own"));

    assertThat(DirectoryTrust.distrust(directory)).isEmpty();
  }

  @Test
  @DisplayName("a world-writable directory without the sticky bit is refused")
  void worldWritableRefused() throws Exception {
    assumePosix();
    final Path directory = Files.createDirectory(tempDir.resolve("open"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

    assertThat(DirectoryTrust.distrust(directory))
        .hasValue("it is world-writable without the sticky bit");
  }

  @Test
  @DisplayName("a world-writable directory with the sticky bit (like /tmp) is trusted")
  void stickyTrusted() throws Exception {
    assumePosix();
    final Path directory = Files.createDirectory(tempDir.resolve("sticky"));
    // java.nio cannot set the sticky bit; chmod can.
    final Process chmod =
        new ProcessBuilder("chmod", "1777", directory.toString()).inheritIO().start();
    assumeTrue(chmod.waitFor() == 0, "chmod 1777 not available");

    assertThat(DirectoryTrust.distrust(directory)).isEmpty();
  }

  @Test
  @DisplayName("a directory in a world-writable parent without the sticky bit is refused")
  void worldWritableParentRefused() throws Exception {
    assumePosix();
    final Path parent = Files.createDirectory(tempDir.resolve("shared"));
    final Path directory = Files.createDirectory(parent.resolve("bridge"));
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwxrwx"));
    final String realParent = parent.toRealPath().toString();

    assertThat(DirectoryTrust.distrust(directory))
        .hasValueSatisfying(
            reason ->
                assertThat(reason)
                    .contains(realParent)
                    .contains("world-writable without the sticky bit"));
  }

  @Test
  @DisplayName("a directory another user owns is refused; root's is trusted")
  void foreignOwnerRefused() throws Exception {
    assumePosix();
    final Path directory = Files.createDirectory(tempDir.resolve("bob"));

    assertThat(DirectoryTrust.distrust(directory, POSIX, path -> "bob", "alice"))
        .hasValue("it is owned by bob, not by the JVM's user alice");
    assertThat(DirectoryTrust.distrust(directory, POSIX, path -> "root", "alice")).isEmpty();
    assertThat(DirectoryTrust.distrust(directory, POSIX, path -> "bob", "?"))
        .as("an unnamed container uid")
        .isEmpty();
  }

  @Test
  @DisplayName("a directory inside another user's directory is refused: that user can rename it")
  void foreignAncestorRefused() throws Exception {
    assumePosix();
    final Path bobs = Files.createDirectory(tempDir.resolve("bobs"));
    final Path directory = Files.createDirectory(bobs.resolve("bridge"));
    final Path realBobs = bobs.toRealPath();

    assertThat(
            DirectoryTrust.distrust(
                directory, POSIX, path -> path.equals(realBobs) ? "bob" : "alice", "alice"))
        .hasValue(
            "the directory "
                + realBobs
                + " on its path is owned by bob, not by the JVM's user alice");
    assertThat(
            DirectoryTrust.distrust(
                directory, POSIX, path -> path.equals(realBobs) ? "root" : "alice", "alice"))
        .isEmpty();
  }

  @Test
  @DisplayName("a path through another user's link is refused: that user can re-point it")
  void foreignLinkRefused() throws Exception {
    assumePosix();
    final Path target = Files.createDirectory(tempDir.resolve("target"));
    final Path link = Files.createSymbolicLink(tempDir.resolve("link"), target);

    assertThat(
            DirectoryTrust.distrust(
                link, POSIX, path -> path.equals(link) ? "bob" : "alice", "alice"))
        .hasValue("the link " + link + " on its path is owned by bob, not by the JVM's user alice");
    assertThat(DirectoryTrust.distrust(link, POSIX, path -> "alice", "alice")).isEmpty();
  }

  @Test
  @DisplayName("a world-writable directory without the sticky bit anywhere above it is refused")
  void worldWritableAncestorRefused() throws Exception {
    assumePosix();
    final Path shared = Files.createDirectory(tempDir.resolve("shared-root"));
    final Path directory = Files.createDirectories(shared.resolve("team").resolve("bridge"));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
    final String realShared = shared.toRealPath().toString();

    assertThat(DirectoryTrust.distrust(directory))
        .hasValueSatisfying(
            reason ->
                assertThat(reason)
                    .contains(realShared)
                    .contains("world-writable without the sticky bit"));
  }

  @Test
  @DisplayName("without a posix view (Windows DOMAIN\\user) only the directory itself is checked")
  void noPosixView() throws Exception {
    final Path directory = Files.createDirectory(tempDir.resolve("windows"));

    assertThat(
            DirectoryTrust.distrust(
                directory, Set.of("basic", "owner", "acl"), path -> "DESKTOP\\alice", "alice"))
        .isEmpty();
  }

  @Test
  @DisplayName("a missing path or a file is refused")
  void missingOrFileRefused() throws Exception {
    final Path file = Files.writeString(tempDir.resolve("file"), "x");

    assertThat(DirectoryTrust.distrust(tempDir.resolve("missing")))
        .hasValueSatisfying(reason -> assertThat(reason).startsWith("it cannot be resolved"));
    assertThat(DirectoryTrust.distrust(file)).hasValue("it is not a directory");
  }

  private static void assumePosix() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }
}
