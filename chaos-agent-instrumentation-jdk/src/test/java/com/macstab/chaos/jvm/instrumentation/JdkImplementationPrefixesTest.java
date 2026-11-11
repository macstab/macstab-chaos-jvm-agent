package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The name prefixes {@link TypeMatchers#jdkNamedOr} lets through are the only JDK classes a
 * hierarchy hook walks: a JDK implementation of a guarded type named otherwise would be skipped
 * silently. Reads every class of every module of the runtime image ({@code jrt:/}, the modules a
 * {@code --add-modules ALL-SYSTEM} run resolves) from its class file, without loading it, and
 * checks each implementation of each guarded type against its hook's prefixes.
 */
@DisplayName("The JDK implementations of the hierarchy hooks' types")
class JdkImplementationPrefixesTest {

  /** Class name to its class file in the runtime image. */
  private static final Map<String, Path> CLASS_FILES = readRuntimeImage();

  private static final TypePool POOL =
      new TypePool.Default(
          new TypePool.CacheProvider.Simple(),
          new RuntimeImageLocator(),
          TypePool.Default.ReaderMode.FAST);

  private static Map<String, Path> readRuntimeImage() {
    final Map<String, Path> classFiles = new HashMap<>();
    try {
      final FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
      try (Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
        for (final Path module : modules.toList()) {
          try (Stream<Path> files = Files.walk(module)) {
            files
                .filter(file -> file.toString().endsWith(".class"))
                .filter(file -> !file.getFileName().toString().equals("module-info.class"))
                .forEach(
                    file -> {
                      final String relative = module.relativize(file).toString();
                      classFiles.put(
                          relative
                              .substring(0, relative.length() - ".class".length())
                              .replace('/', '.'),
                          file);
                    });
          }
        }
      }
    } catch (final IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
    return classFiles;
  }

  /** The class files of the runtime image, by class name. */
  private static final class RuntimeImageLocator implements ClassFileLocator {
    @Override
    public Resolution locate(final String name) throws IOException {
      final Path file = CLASS_FILES.get(name);
      return file == null
          ? new Resolution.Illegal(name)
          : new Resolution.Explicit(Files.readAllBytes(file));
    }

    @Override
    public void close() {
      // nothing held open
    }
  }

  /** The non-interface classes of the runtime image that implement or extend {@code type}. */
  private static List<String> implementations(final String type) {
    final TypeDescription guarded = POOL.describe(type).resolve();
    final List<String> found = new ArrayList<>();
    for (final String name : CLASS_FILES.keySet()) {
      final TypeDescription candidate;
      try {
        candidate = POOL.describe(name).resolve();
        if (candidate.isInterface()
            || candidate.equals(guarded)
            || !candidate.isAssignableTo(guarded)) {
          continue;
        }
      } catch (final IllegalStateException | UncheckedIOException unresolvable) {
        continue; // a supertype outside the image (none expected)
      }
      found.add(name);
    }
    return found;
  }

  @Test
  @DisplayName("each carries a prefix its hook lets through; JDBC types have none in the JDK")
  void implementationsCarryTheirGroupsPrefix() {
    final Map<String, String> prefixByType = new LinkedHashMap<>();
    prefixByType.put("java.nio.channels.SocketChannel", TypeMatchers.JDK_CHANNELS);
    prefixByType.put("java.nio.channels.ServerSocketChannel", TypeMatchers.JDK_CHANNELS);
    prefixByType.put("java.nio.channels.spi.AbstractSelector", TypeMatchers.JDK_CHANNELS);
    prefixByType.put("javax.net.ssl.SSLSocket", TypeMatchers.JDK_TLS);
    prefixByType.put("javax.net.ssl.SSLEngine", TypeMatchers.JDK_TLS);
    prefixByType.put("javax.management.MBeanServer", TypeMatchers.JDK_JMX);
    prefixByType.put("java.util.concurrent.BlockingQueue", TypeMatchers.JDK_CONCURRENT);

    for (final Map.Entry<String, String> guarded : prefixByType.entrySet()) {
      final List<String> implementations = implementations(guarded.getKey());
      assertThat(implementations).as("implementations of %s", guarded.getKey()).isNotEmpty();
      assertThat(implementations)
          .as("implementations of %s", guarded.getKey())
          .allMatch(name -> name.startsWith(guarded.getValue()));
    }
    for (final String jdbc :
        List.of("java.sql.Statement", "java.sql.PreparedStatement", "java.sql.Connection")) {
      assertThat(implementations(jdbc)).as("implementations of %s", jdbc).isEmpty();
    }
  }

  @Test
  @DisplayName("no JDK subclass of ThreadPoolExecutor overrides beforeExecute")
  void noJdkBeforeExecuteOverride() {
    final List<String> overriding = new ArrayList<>();
    for (final String name : implementations("java.util.concurrent.ThreadPoolExecutor")) {
      for (final MethodDescription.InDefinedShape method :
          POOL.describe(name).resolve().getDeclaredMethods()) {
        if (method.getName().equals("beforeExecute")
            && method.getParameters().size() == 2
            && method.getParameters().get(0).getType().asErasure().represents(Thread.class)
            && method.getParameters().get(1).getType().asErasure().represents(Runnable.class)) {
          overriding.add(name);
        }
      }
    }
    // The group weaves ThreadPoolExecutor's own beforeExecute by name; jdkNamedOr gives the
    // override rule no JDK prefix.
    assertThat(overriding).isEmpty();
  }
}
