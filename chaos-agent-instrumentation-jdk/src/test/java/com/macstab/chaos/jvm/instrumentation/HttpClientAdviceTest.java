package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HttpClientAdvice / HTTP bridge")
class HttpClientAdviceTest {

  private ChaosRuntime runtime;
  private ChaosBridge bridge;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    bridge = new ChaosBridge(runtime);
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("beforeHttpSend passthrough")
  class HttpSendPassthrough {

    @Test
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeHttpSend("https://api.example.com/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeHttpSend("https://api.example.com/"))
          .doesNotThrowAnyException();
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
    @DisplayName("passes through when no bridge is installed (fallback)")
    void passesWithoutBridge() {
      assertThatCode(() -> BootstrapDispatcher.beforeHttpSendAsync("https://api.example.com/"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes through when no scenario matches")
    void passesWithoutScenarios() {
      assertThatCode(() -> bridge.beforeHttpSendAsync("https://api.example.com/"))
          .doesNotThrowAnyException();
    }
  }
}
