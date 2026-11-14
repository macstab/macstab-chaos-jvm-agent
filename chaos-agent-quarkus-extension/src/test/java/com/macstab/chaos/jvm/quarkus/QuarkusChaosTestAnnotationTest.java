package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("@QuarkusChaosTest")
class QuarkusChaosTestAnnotationTest {

  @QuarkusChaosTest
  private static class AnnotatedBase {}

  private static final class Subclass extends AnnotatedBase {}

  @Test
  @DisplayName("a subclass of an annotated test class is a chaos test too")
  void isInherited() {
    assertThat(QuarkusChaosTest.class.isAnnotationPresent(Inherited.class)).isTrue();
    assertThat(Subclass.class.isAnnotationPresent(QuarkusChaosTest.class)).isTrue();
  }

  @Test
  @DisplayName("appears in the Javadoc of the classes it annotates")
  void isDocumented() {
    assertThat(QuarkusChaosTest.class.isAnnotationPresent(Documented.class)).isTrue();
  }
}
