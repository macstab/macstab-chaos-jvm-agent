package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HookCoverage")
class HookCoverageTest {

  /** A stand-in advice: only its name is used. */
  static final class SampleAdvice {}

  /** Another stand-in. */
  static final class OtherAdvice {}

  private static MethodDescription method(final String name) {
    return TypeDescription.ForLoadedType.of(String.class)
        .getDeclaredMethods()
        .filter(ElementMatchers.named(name))
        .getFirst();
  }

  @Test
  @DisplayName("a hook that matched a method of any type is active")
  void matchedHookIsActive() {
    final ElementMatcher<MethodDescription> hook =
        HookCoverage.track(SampleAdvice.class, ElementMatchers.named("length"));
    assertThat(hook.matches(method("isEmpty"))).isFalse();
    assertThat(hook.matches(method("length"))).isTrue();
    assertThat(HookCoverage.matched())
        .anyMatch(id -> id.startsWith("HookCoverageTest.SampleAdvice on "));
    assertThat(HookCoverage.unmatched())
        .noneMatch(id -> id.startsWith("HookCoverageTest.SampleAdvice on "));
  }

  @Test
  @DisplayName("a hook woven into a type but matching no method is reported inactive")
  void unmatchedHookIsReported() {
    final ElementMatcher<MethodDescription> hook =
        HookCoverage.track(OtherAdvice.class, ElementMatchers.named("noSuchMethod"));
    assertThat(hook.matches(method("length"))).isFalse();
    assertThat(HookCoverage.unmatched())
        .anyMatch(
            id -> id.startsWith("HookCoverageTest.OtherAdvice on name(equals(noSuchMethod))"));
  }

  @Test
  @DisplayName("a failed transformation is reported with its type and cause")
  void failureIsReported() {
    HookCoverage.failed("com.example.Old", new IllegalStateException("class file version 50"));
    assertThat(HookCoverage.failures())
        .contains("com.example.Old: java.lang.IllegalStateException: class file version 50");
  }
}
