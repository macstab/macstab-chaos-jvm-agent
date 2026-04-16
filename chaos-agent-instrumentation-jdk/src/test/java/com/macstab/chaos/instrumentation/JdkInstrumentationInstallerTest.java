package com.macstab.chaos.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.DisplayName;
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
}
