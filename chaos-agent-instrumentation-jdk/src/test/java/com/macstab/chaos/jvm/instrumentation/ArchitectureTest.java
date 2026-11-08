package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules of the agent's architecture that the module graph cannot express, each one a class of
 * runtime failure no compiler sees: a bridge class missing from the bootstrap jar ({@code
 * NoClassDefFoundError} inside a JDK class), inlined advice calling a non-public agent member
 * ({@code IllegalAccessError} inside a foreign class), inlined advice referencing an agent class
 * that is not on the bootstrap class path ({@code NoClassDefFoundError} inside a library class
 * whose loader cannot see the agent's), inlined advice holding an {@code invokedynamic} ({@code
 * VerifyError} inside a class file older than version 51), and domain dispatchers coupled to each
 * other instead of to the engine.
 */
@DisplayName("Architecture rules")
class ArchitectureTest {

  private static final String AGENT = "com.macstab.chaos.jvm.";
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.macstab.chaos.jvm");
  }

  @Test
  @DisplayName(
      "the bootstrap bridge jar is closed: its classes reference no agent class outside it")
  void bridgeIsClosed() {
    final Set<String> bridge = bridgeClassNames();
    final List<String> escapes = new ArrayList<>();
    for (final String name : bridge) {
      assertThat(classes.contain(name)).as("bridge class %s exists", name).isTrue();
      for (final Dependency dependency : classes.get(name).getDirectDependenciesFromSelf()) {
        final String target = dependency.getTargetClass().getBaseComponentType().getName();
        if (target.startsWith(AGENT) && !bridge.contains(target)) {
          escapes.add(dependency.getDescription());
        }
      }
    }
    assertThat(escapes).as("references the bootstrap class loader cannot resolve").isEmpty();
  }

  @Test
  @DisplayName("inlined advice accesses only public members of public agent classes")
  void adviceAccessesOnlyPublicAgentMembers() {
    final List<String> violations = new ArrayList<>();
    for (final JavaClass advice : classes) {
      if (!isAdvice(advice)) {
        continue;
      }
      for (final JavaAccess<?> access : advice.getAccessesFromSelf()) {
        final AccessTarget target = access.getTarget();
        final JavaClass owner = target.getOwner();
        if (!owner.getName().startsWith(AGENT) || isAdvice(owner)) {
          continue;
        }
        final boolean publicMember =
            target
                .resolveMember()
                .map(member -> member.getModifiers().contains(JavaModifier.PUBLIC))
                .orElse(false);
        if (!owner.getModifiers().contains(JavaModifier.PUBLIC) || !publicMember) {
          violations.add(access.getDescription());
        }
      }
    }
    assertThat(violations).as("accesses that fail once the advice is inlined elsewhere").isEmpty();
  }

  @Test
  @DisplayName("inlined advice references only bridge classes of the agent")
  void adviceReferencesOnlyBridgeClasses() {
    final Set<String> bridge = bridgeClassNames();
    final List<String> violations = new ArrayList<>();
    for (final JavaClass advice : classes) {
      if (!isAdvice(advice)) {
        continue;
      }
      for (final Dependency dependency : advice.getDirectDependenciesFromSelf()) {
        final JavaClass target = dependency.getTargetClass().getBaseComponentType();
        if (target.getName().startsWith(AGENT)
            && !isAdvice(target)
            && !bridge.contains(target.getName())) {
          violations.add(dependency.getDescription());
        }
      }
    }
    assertThat(violations)
        .as("references a library class loader that only reaches the boot path cannot resolve")
        .isEmpty();
  }

  @Test
  @DisplayName("inlined advice holds no invokedynamic (string concatenation, lambdas)")
  void adviceHoldsNoInvokedynamic() throws IOException {
    final List<String> violations = new ArrayList<>();
    for (final JavaClass advice : classes) {
      if (isAdvice(advice)) {
        violations.addAll(invokedynamicSites(advice.getName()));
      }
    }
    assertThat(violations)
        .as("invokedynamic is illegal in class files below version 51 (Java 6 drivers and clients)")
        .isEmpty();
  }

  /** Returns {@code Class.method} for every {@code invokedynamic} in the named class's methods. */
  private static List<String> invokedynamicSites(final String className) throws IOException {
    final List<String> sites = new ArrayList<>();
    try (InputStream classFile =
        ArchitectureTest.class
            .getClassLoader()
            .getResourceAsStream(className.replace('.', '/') + ".class")) {
      assertThat(classFile).as("class file of %s", className).isNotNull();
      new ClassReader(classFile)
          .accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                  return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(
                        final String indyName,
                        final String indyDescriptor,
                        final Handle bootstrap,
                        final Object... bootstrapArguments) {
                      sites.add(className + "." + name + " -> " + bootstrap.getOwner());
                    }
                  };
                }
              },
              ClassReader.SKIP_DEBUG);
    }
    return sites;
  }

  @Test
  @DisplayName("the domain dispatchers depend on the engine, not on each other")
  void domainDispatchersAreIndependent() {
    final List<String> couplings = new ArrayList<>();
    for (final JavaClass domain : classes) {
      if (!isDomainDispatch(domain)) {
        continue;
      }
      for (final Dependency dependency : domain.getDirectDependenciesFromSelf()) {
        final JavaClass target = dependency.getTargetClass();
        if (isDomainDispatch(target) && !target.equals(domain)) {
          couplings.add(dependency.getDescription());
        }
      }
    }
    assertThat(classes.stream().filter(ArchitectureTest::isDomainDispatch)).hasSize(10);
    assertThat(couplings).isEmpty();
  }

  @Test
  @DisplayName(
      "the API depends on nothing of the agent; the core not on instrumentation or parsing")
  void layering() {
    final List<String> violations = new ArrayList<>();
    for (final JavaClass type : classes) {
      final String layer = layerOf(type.getPackageName());
      if (!layer.equals("api") && !layer.equals("core")) {
        continue;
      }
      for (final Dependency dependency : type.getDirectDependenciesFromSelf()) {
        final String target = dependency.getTargetClass().getBaseComponentType().getPackageName();
        final String targetLayer = layerOf(target);
        final boolean allowed =
            layer.equals("api")
                ? targetLayer.equals("api") || !target.startsWith("com.macstab")
                : !Set.of("instrumentation", "startup", "bootstrap").contains(targetLayer)
                    // Stressors generate classes to fill the metaspace and the code cache; nothing
                    // else in the core, and nothing on the dispatch path, touches ByteBuddy.
                    && (!target.startsWith("net.bytebuddy")
                        || type.getSimpleName().endsWith("Stressor"))
                    && !target.startsWith("com.fasterxml.jackson");
        if (!allowed) {
          violations.add(dependency.getDescription());
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  private static Set<String> bridgeClassNames() {
    return BridgeInjector.BRIDGE_CLASSES.stream()
        .map(path -> path.substring(0, path.length() - ".class".length()).replace('/', '.'))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static boolean isAdvice(final JavaClass type) {
    return type.getPackageName().equals("com.macstab.chaos.jvm.instrumentation")
        && type.getName()
            .substring(type.getName().lastIndexOf('.') + 1)
            .matches("\\w*Advice(\\$.*)?");
  }

  private static boolean isDomainDispatch(final JavaClass type) {
    return type.getPackageName().equals("com.macstab.chaos.jvm.core")
        && type.getSimpleName().endsWith("Dispatch")
        && !type.isNestedClass();
  }

  private static String layerOf(final String packageName) {
    if (!packageName.startsWith(AGENT)) {
      return "";
    }
    final String rest = packageName.substring(AGENT.length());
    final int dot = rest.indexOf('.');
    return dot < 0 ? rest : rest.substring(0, dot);
  }
}
