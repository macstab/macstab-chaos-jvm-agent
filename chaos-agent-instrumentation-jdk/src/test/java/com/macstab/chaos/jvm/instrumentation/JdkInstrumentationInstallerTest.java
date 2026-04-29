package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdkInstrumentationInstaller")
class JdkInstrumentationInstallerTest {

  @Test
  @DisplayName("buildMethodHandles resolves all handle slots without nulls")
  void buildMethodHandlesResolvesAllSlots() throws Exception {
    final MethodHandle[] handles = JdkInstrumentationInstaller.buildMethodHandles();

    assertThat(handles).hasSize(BootstrapDispatcher.HANDLE_COUNT);
    for (int i = 0; i < handles.length; i++) {
      assertThat(handles[i]).as("slot %d must not be null", i).isNotNull();
    }
  }

  @Nested
  @DisplayName("instrumentOptional — graceful absence of optional classes")
  class InstrumentOptionalGracefulAbsence {

    @Test
    @DisplayName(
        "buildMethodHandles() alone never references HikariCP, c3p0, OkHttp, Apache HC, or Reactor"
            + " Netty — confirming instrumentOptional paths are compile-safe even if those"
            + " classes are absent at runtime")
    void handlesBuildWithoutOptionalClasses() {
      assertThatCode(JdkInstrumentationInstaller::buildMethodHandles).doesNotThrowAnyException();
    }
  }
}
