package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PathPattern")
class PathPatternTest {

  @Test
  @DisplayName("a path matches through its single-dot segments")
  void singleDotSegments() {
    final PathPattern pattern = PathPattern.under("/data/x");

    assertThat(pattern.matches("/data/./x")).isTrue();
    assertThat(pattern.matches("/data/./x/file")).isTrue();
    assertThat(pattern.matches("/data/./y")).isFalse();
  }

  @Test
  @DisplayName("a path matches through its parent segments")
  void parentSegments() {
    final PathPattern pattern = PathPattern.under("/data/x");

    assertThat(pattern.matches("/data/y/../x/file")).isTrue();
    assertThat(pattern.matches("/data/x/../y")).isFalse();
  }

  @Test
  @DisplayName("a sibling sharing the prefix's spelling is not under it")
  void siblingIsNotUnder() {
    assertThat(PathPattern.under("/data/x").matches("/data/xy")).isFalse();
    assertThat(PathPattern.under("/data/x").matches(null)).isFalse();
  }

  @Test
  @DisplayName("the prefix is stored normalised and must be non-blank")
  void prefix() {
    assertThat(PathPattern.under("/data//x/./").prefix()).isEqualTo("/data/x");
    assertThatThrownBy(() -> PathPattern.under(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("path prefix must be non-blank");
  }
}
