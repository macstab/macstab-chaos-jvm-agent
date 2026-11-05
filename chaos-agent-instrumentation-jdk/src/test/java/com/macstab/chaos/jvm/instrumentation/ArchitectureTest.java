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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules of the agent's architecture that the module graph cannot express, each one a class of
 * runtime failure no compiler sees: a bridge class missing from the bootstrap jar ({@code
 * NoClassDefFoundError} inside a JDK class), inlined advice calling a non-public agent member
 * ({@code IllegalAccessError} inside a foreign class), and domain dispatchers coupled to each other
 * instead of to the engine.
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
    final Set<String> bridge =
        BridgeInjector.BRIDGE_CLASSES.stream()
            .map(path -> path.substring(0, path.length() - ".class".length()).replace('/', '.'))
            .collect(Collectors.toCollection(TreeSet::new));
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
