package com.macstab.chaos.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The public API of a module's class files, as committed in {@code api/<module>.api}: every public
 * or protected class, field, constructor and method, with JVM descriptors (so a change of a
 * parameter, return or field type shows), and the permitted subtypes of sealed types (adding one
 * breaks exhaustive switches). Types and members annotated {@code @Internal} are left out. The
 * descriptors are erased: generic type arguments, annotations and the order of enum constants are
 * not part of the record.
 */
public final class ApiSignatures {

  private static final String INTERNAL = "Lcom/macstab/chaos/jvm/api/Internal;";
  private static final Set<String> KINDS =
      Set.of("class", "interface", "enum", "record", "@interface");

  private ApiSignatures() {}

  private record Type(String name, String text, String outer, int flags, boolean internal) {}

  /**
   * Renders the API of the class files in {@code classesDir}: one block per type, its header line
   * first, the blocks separated by a blank line and sorted by type name.
   */
  public static String render(final File classesDir) {
    final Map<String, Type> types = new HashMap<>();
    final List<Path> files;
    try (Stream<Path> walk = Files.walk(classesDir.toPath())) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".class"))
              .filter(path -> !path.getFileName().toString().equals("module-info.class"))
              .toList();
    } catch (final IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
    for (final Path file : files) {
      final Type type = read(file);
      types.put(type.name(), type);
    }
    return types.values().stream()
        .filter(type -> exported(type, types))
        .sorted((a, b) -> a.name().compareTo(b.name()))
        .map(Type::text)
        .collect(Collectors.joining("\n\n", "", "\n"));
  }

  private static Type read(final Path file) {
    final ClassReader reader;
    try {
      reader = new ClassReader(Files.readAllBytes(file));
    } catch (final IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
    final List<String> lines = new ArrayList<>();
    final List<String> permits = new ArrayList<>();
    final int[] flags = {reader.getAccess()};
    final String[] outer = {null};
    final boolean[] internal = {false};
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            if (descriptor.equals(INTERNAL)) {
              internal[0] = true;
            }
            return null;
          }

          @Override
          public void visitInnerClass(
              final String name,
              final String outerName,
              final String innerName,
              final int innerAccess) {
            if (name.equals(reader.getClassName())) {
              flags[0] = innerAccess;
              outer[0] = outerName;
            }
          }

          @Override
          public void visitPermittedSubclass(final String permittedSubclass) {
            permits.add(permittedSubclass);
          }

          @Override
          public FieldVisitor visitField(
              final int access,
              final String name,
              final String descriptor,
              final String signature,
              final Object value) {
            if (!visible(access)) {
              return null;
            }
            return new FieldVisitor(Opcodes.ASM9) {
              private boolean memberInternal;

              @Override
              public AnnotationVisitor visitAnnotation(
                  final String annotation, final boolean visible) {
                if (annotation.equals(INTERNAL)) {
                  memberInternal = true;
                }
                return null;
              }

              @Override
              public void visitEnd() {
                if (!memberInternal) {
                  lines.add("  " + access(access) + " field " + name + " " + descriptor);
                }
              }
            };
          }

          @Override
          public MethodVisitor visitMethod(
              final int access,
              final String name,
              final String descriptor,
              final String signature,
              final String[] exceptions) {
            if (!visible(access) || (access & Opcodes.ACC_BRIDGE) != 0) {
              return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
              private boolean memberInternal;

              @Override
              public AnnotationVisitor visitAnnotation(
                  final String annotation, final boolean visible) {
                if (annotation.equals(INTERNAL)) {
                  memberInternal = true;
                }
                return null;
              }

              @Override
              public void visitEnd() {
                if (!memberInternal) {
                  lines.add("  " + access(access) + " method " + name + descriptor);
                }
              }
            };
          }
        },
        ClassReader.SKIP_CODE);
    final int access = flags[0];
    final String kind;
    if ((access & Opcodes.ACC_ANNOTATION) != 0) {
      kind = "@interface";
    } else if ((access & Opcodes.ACC_INTERFACE) != 0) {
      kind = "interface";
    } else if ((access & Opcodes.ACC_ENUM) != 0) {
      kind = "enum";
    } else if ("java/lang/Record".equals(reader.getSuperName())) {
      kind = "record";
    } else {
      kind = "class";
    }
    final boolean anInterface = kind.equals("interface") || kind.equals("@interface");
    final int headerFlags =
        access & ~Opcodes.ACC_INTERFACE & (anInterface ? ~Opcodes.ACC_ABSTRACT : -1);
    final StringBuilder header = new StringBuilder();
    header.append(access(headerFlags)).append(' ').append(kind).append(' ');
    header.append(reader.getClassName());
    final String superName = reader.getSuperName();
    if (superName != null && !superName.equals("java/lang/Object") && kind.equals("class")) {
      header.append(" extends ").append(superName);
    }
    if (reader.getInterfaces().length > 0) {
      final List<String> interfaces = new ArrayList<>(List.of(reader.getInterfaces()));
      Collections.sort(interfaces);
      header.append(" implements ").append(String.join(", ", interfaces));
    }
    if (!permits.isEmpty()) {
      Collections.sort(permits);
      header.append(" permits ").append(String.join(", ", permits));
    }
    Collections.sort(lines);
    final List<String> text = new ArrayList<>();
    text.add(header.toString());
    text.addAll(lines);
    return new Type(reader.getClassName(), String.join("\n", text), outer[0], access, internal[0]);
  }

  /** A type is API when it and every type enclosing it are visible and not {@code @Internal}. */
  private static boolean exported(final Type type, final Map<String, Type> types) {
    if (!visible(type.flags()) || type.internal()) {
      return false;
    }
    if (type.outer() == null) {
      return true;
    }
    final Type outer = types.get(type.outer());
    return outer != null && exported(outer, types);
  }

  private static boolean visible(final int flags) {
    return (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
        && (flags & Opcodes.ACC_SYNTHETIC) == 0;
  }

  private static String access(final int flags) {
    final List<String> words = new ArrayList<>();
    if ((flags & Opcodes.ACC_PUBLIC) != 0) {
      words.add("public");
    }
    if ((flags & Opcodes.ACC_PROTECTED) != 0) {
      words.add("protected");
    }
    if ((flags & Opcodes.ACC_STATIC) != 0) {
      words.add("static");
    }
    if ((flags & Opcodes.ACC_FINAL) != 0) {
      words.add("final");
    }
    if ((flags & Opcodes.ACC_ABSTRACT) != 0) {
      words.add("abstract");
    }
    return String.join(" ", words);
  }

  /**
   * The difference between two renderings, per type: a type only in one of them, or the header of a
   * type whose signatures differ followed by its removed (-) and added (+) lines.
   */
  public static String difference(final String committed, final String actual) {
    final Map<String, List<String>> before = blocks(committed);
    final Map<String, List<String>> after = blocks(actual);
    final Set<String> names = new TreeSet<>(before.keySet());
    names.addAll(after.keySet());
    final List<String> parts = new ArrayList<>();
    for (final String name : names) {
      final List<String> old = before.get(name);
      final List<String> now = after.get(name);
      if (old != null && old.equals(now)) {
        continue;
      }
      final List<String> part = new ArrayList<>();
      if (old == null) {
        now.forEach(line -> part.add("+ " + line));
      } else if (now == null) {
        old.forEach(line -> part.add("- " + line));
      } else {
        if (old.get(0).equals(now.get(0))) {
          part.add("  " + now.get(0));
        } else {
          part.add("- " + old.get(0));
          part.add("+ " + now.get(0));
        }
        final Set<String> nowMembers = Set.copyOf(now.subList(1, now.size()));
        final Set<String> oldMembers = Set.copyOf(old.subList(1, old.size()));
        old.subList(1, old.size()).stream()
            .filter(line -> !nowMembers.contains(line))
            .forEach(line -> part.add("- " + line));
        now.subList(1, now.size()).stream()
            .filter(line -> !oldMembers.contains(line))
            .forEach(line -> part.add("+ " + line));
      }
      parts.add(String.join("\n", part));
    }
    return String.join("\n\n", parts);
  }

  private static Map<String, List<String>> blocks(final String api) {
    final Map<String, List<String>> blocks = new LinkedHashMap<>();
    for (final String block : api.trim().split("\n\n")) {
      if (block.isBlank()) {
        continue;
      }
      final List<String> lines = List.of(block.split("\n", -1));
      final String[] words = lines.get(0).split(" ");
      int kind = 0;
      while (kind < words.length && !KINDS.contains(words[kind])) {
        kind++;
      }
      blocks.put(words[kind + 1], lines);
    }
    return blocks;
  }
}
