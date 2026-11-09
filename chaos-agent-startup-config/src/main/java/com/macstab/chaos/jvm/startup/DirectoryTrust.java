package com.macstab.chaos.jvm.startup;

import com.macstab.chaos.jvm.api.Internal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The trust check of a directory the agent writes code into ({@code bridgeDir}): the directory must
 * be neither world-writable without the sticky bit nor owned by another user than the JVM's (root
 * excepted), and its parent not world-writable without the sticky bit. Either would let another
 * local user replace what the agent writes there. The plan file follows the same rule ({@link
 * StartupConfigLoader}). Modes and owners are POSIX notions: on a file system without a {@code
 * posix} view (Windows) only the existence of the directory is checked.
 */
@Internal
public final class DirectoryTrust {

  private static final int POSIX_STICKY_BIT = 01000;

  private DirectoryTrust() {}

  /**
   * Returns why a directory is not trusted.
   *
   * @param directory the directory, as configured
   * @return the reason, or empty when the directory is trusted
   */
  public static Optional<String> distrust(final Path directory) {
    return distrust(
        directory,
        directory.getFileSystem().supportedFileAttributeViews(),
        DirectoryTrust::ownerName,
        System.getProperty("user.name", ""));
  }

  /**
   * Test seam of {@link #distrust(Path)}: the file system's attribute views, the owner lookup and
   * the JVM's user name are passed in.
   *
   * @param directory the directory, as configured
   * @param supportedViews the attribute views of the directory's file system
   * @param ownerOf the owner name of a path; throws {@link UncheckedIOException} or {@link
   *     UnsupportedOperationException} where it is unavailable
   * @param jvmUser the JVM's user name ({@code user.name})
   * @return the reason, or empty when the directory is trusted
   */
  static Optional<String> distrust(
      final Path directory,
      final Set<String> supportedViews,
      final Function<Path, String> ownerOf,
      final String jvmUser) {
    final Path real;
    try {
      real = directory.toRealPath();
    } catch (final IOException missing) {
      return Optional.of("it cannot be resolved (" + missing + ")");
    }
    if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.of("it is not a directory");
    }
    if (!supportedViews.contains("posix")) {
      return Optional.empty();
    }
    try {
      if (worldWritableWithoutSticky(real)) {
        return Optional.of("it is world-writable without the sticky bit");
      }
      final Path parent = real.getParent();
      if (parent != null && worldWritableWithoutSticky(parent)) {
        return Optional.of(
            "its parent directory " + parent + " is world-writable without the sticky bit");
      }
    } catch (final IOException | UnsupportedOperationException unreadable) {
      return Optional.of("its permissions cannot be read (" + unreadable + ")");
    }
    if (jvmUser.isBlank() || jvmUser.equals("?")) {
      return Optional.empty();
    }
    final String owner;
    try {
      owner = ownerOf.apply(real);
    } catch (final UncheckedIOException | UnsupportedOperationException unavailable) {
      return Optional.empty();
    }
    if (!owner.equals(jvmUser) && !owner.equals("root")) {
      return Optional.of("it is owned by " + owner + ", not by the JVM's user " + jvmUser);
    }
    return Optional.empty();
  }

  /**
   * Whether a directory (its real path) is world-writable without the sticky bit: any local user
   * can then replace the entries in it by unlink and create, even a file of mode 0644. The sticky
   * bit (as on {@code /tmp}) restricts unlink to the entry's owner.
   *
   * @throws IOException if the mode cannot be read
   */
  static boolean worldWritableWithoutSticky(final Path directory) throws IOException {
    return Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
            .contains(PosixFilePermission.OTHERS_WRITE)
        && !isStickyBitSet(directory);
  }

  private static boolean isStickyBitSet(final Path directory) {
    // POSIX sticky bit is not exposed by Files.getPosixFilePermissions (only the nine rwxrwxrwx
    // mode bits are). Use the Unix-specific "unix:mode" attribute view where available, which
    // returns the full 16-bit mode including setuid/setgid/sticky in the high bits. On
    // filesystems that don't expose it, assume sticky is NOT set — fail-closed.
    try {
      final Object mode = Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
      if (mode instanceof Integer modeValue) {
        return (modeValue & POSIX_STICKY_BIT) != 0;
      }
    } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
      // Fall through to fail-closed.
    }
    return false;
  }

  private static String ownerName(final Path path) {
    try {
      return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
    } catch (final IOException unavailable) {
      throw new UncheckedIOException(unavailable);
    }
  }
}
