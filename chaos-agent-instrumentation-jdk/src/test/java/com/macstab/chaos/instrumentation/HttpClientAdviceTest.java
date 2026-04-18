package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HttpClientAdvice / HTTP bridge")
class HttpClientAdviceTest {

  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    bridge = new ChaosBridge(new ChaosRuntime());
  }

  @Nested
  @DisplayName("beforeHttpSend passthrough")
  class HttpSendPassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeHttpSend("https://api.example.com/")).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeHttpSend("https://api.example.com/")).isFalse();
    }

    @Test
    @DisplayName("accepts null URL without throwing")
    void nullUrlIsSafe() {
      assertThatCode(() -> bridge.beforeHttpSend(null)).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("beforeHttpSendAsync passthrough")
  class HttpSendAsyncPassthrough {

    @Test
    @DisplayName("returns false when no bridge installed (fallback)")
    void returnsFalseWithoutBridge() throws Throwable {
      assertThat(BootstrapDispatcher.beforeHttpSendAsync("https://api.example.com/")).isFalse();
    }

    @Test
    @DisplayName("returns false when no scenarios match")
    void returnsFalseWithoutScenarios() throws Throwable {
      assertThat(bridge.beforeHttpSendAsync("https://api.example.com/")).isFalse();
    }
  }
}
