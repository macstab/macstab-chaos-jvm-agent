package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TypeMatchers")
class TypeMatchersTest {

  /** {@code probe.Orphan extends probe.Parent}, described by a pool that has no {@code Parent}. */
  private static TypeDescription orphan() {
    final DynamicType.Unloaded<Object> parent =
        new ByteBuddy().subclass(Object.class).name("probe.Parent").make();
    final byte[] orphan =
        new ByteBuddy()
            .subclass(parent.getTypeDescription())
            .name("probe.Orphan")
            .make()
            .getBytes();
    final TypePool pool =
        TypePool.Default.of(
            new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of("probe.Orphan", orphan),
                ClassFileLocator.ForClassLoader.ofSystemLoader()));
    return pool.describe("probe.Orphan").resolve();
  }

  @Test
  @DisplayName("a type whose superclass cannot be resolved matches none of them")
  void unresolvableSupertypeIsNoMatch() {
    final TypeDescription orphan = orphan();

    assertThatThrownBy(() -> ElementMatchers.isSubTypeOf(Runnable.class).matches(orphan))
        .as("the plain matcher fails on the missing superclass")
        .isInstanceOf(TypePool.Resolution.NoSuchTypeException.class);
    assertThat(TypeMatchers.subTypeOf(Runnable.class).matches(orphan)).isFalse();
    assertThat(TypeMatchers.superType(ElementMatchers.named("java.lang.Runnable")).matches(orphan))
        .isFalse();
    assertThat(TypeMatchers.superClass(ElementMatchers.named("java.lang.Object")).matches(orphan))
        .isFalse();
  }

  @Test
  @DisplayName("resolvable types match as with ElementMatchers")
  void resolvableTypesMatch() {
    final TypeDescription queue = TypeDescription.ForLoadedType.of(ConcurrentLinkedQueue.class);

    assertThat(TypeMatchers.subTypeOf(java.util.Queue.class).matches(queue)).isTrue();
    assertThat(TypeMatchers.subTypeOf(Runnable.class).matches(queue)).isFalse();
    assertThat(TypeMatchers.superType(ElementMatchers.named("java.util.Queue")).matches(queue))
        .isTrue();
    assertThat(
            TypeMatchers.superClass(ElementMatchers.named("java.util.AbstractQueue"))
                .matches(queue))
        .isTrue();
    assertThat(TypeMatchers.subTypeOf(java.util.Queue.class))
        .hasToString(ElementMatchers.isSubTypeOf(java.util.Queue.class).toString());
  }

  /** An application's own BlockingQueue. */
  abstract static class AppQueue<E> extends java.util.concurrent.LinkedBlockingQueue<E> {}

  @Test
  @DisplayName("a JDK class is walked only when named with a JDK prefix; any other class always")
  void jdkClassesNeedTheirPrefix() {
    final net.bytebuddy.agent.builder.AgentBuilder.RawMatcher queues =
        TypeMatchers.jdkNamedOr(
            TypeMatchers.subTypeOf(java.util.concurrent.BlockingQueue.class),
            "java.util.concurrent.");
    final net.bytebuddy.agent.builder.AgentBuilder.RawMatcher noJdkImplementation =
        TypeMatchers.jdkNamedOr(TypeMatchers.subTypeOf(java.util.concurrent.BlockingQueue.class));
    final TypeDescription jdkQueue =
        TypeDescription.ForLoadedType.of(java.util.concurrent.ArrayBlockingQueue.class);
    final TypeDescription appQueue = TypeDescription.ForLoadedType.of(AppQueue.class);
    final net.bytebuddy.utility.JavaModule javaBase =
        net.bytebuddy.utility.JavaModule.ofType(java.util.concurrent.ArrayBlockingQueue.class);
    final net.bytebuddy.utility.JavaModule unnamed =
        net.bytebuddy.utility.JavaModule.ofType(AppQueue.class);
    final ClassLoader app = AppQueue.class.getClassLoader();

    assertThat(queues.matches(jdkQueue, null, javaBase, null, null)).as("JDK, prefixed").isTrue();
    assertThat(noJdkImplementation.matches(jdkQueue, null, javaBase, null, null))
        .as("JDK, not prefixed: not walked")
        .isFalse();
    assertThat(
            noJdkImplementation.matches(
                jdkQueue, ClassLoader.getPlatformClassLoader(), javaBase, null, null))
        .as("platform loader, not prefixed")
        .isFalse();
    assertThat(noJdkImplementation.matches(appQueue, app, unnamed, null, null))
        .as("an application class is walked")
        .isTrue();
    assertThat(noJdkImplementation.matches(appQueue, null, unnamed, null, null))
        .as("a class appended to the boot class path is walked")
        .isTrue();
    assertThat(noJdkImplementation.matches(appQueue, null, null, null, null))
        .as("no module known: walked")
        .isTrue();
    assertThat(queues.toString()).contains("java.util.concurrent.");
  }

  @Test
  @DisplayName("any other failure of the matcher is not swallowed")
  void otherFailuresPropagate() {
    final ElementMatcher<TypeDescription> failing =
        target -> {
          throw new IllegalStateException("weaving failure");
        };

    assertThatThrownBy(
            () ->
                TypeMatchers.superType(failing)
                    .matches(TypeDescription.ForLoadedType.of(ConcurrentLinkedQueue.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("weaving failure");
  }
}
