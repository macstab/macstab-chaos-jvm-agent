package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.core.ChaosRuntime;
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
  @DisplayName("connectDispatcher")
  class ConnectDispatcher {

    @Test
    @DisplayName("closing a connection leaves a later connection's delegate in place")
    void closeKeepsALaterDelegate() throws Exception {
      final AutoCloseable first = JdkInstrumentationInstaller.connectDispatcher(new ChaosRuntime());
      final AutoCloseable second =
          JdkInstrumentationInstaller.connectDispatcher(new ChaosRuntime());
      try {
        final Object later = BootstrapDispatcher.installedDelegate();

        first.close();

        assertThat(BootstrapDispatcher.installedDelegate()).isSameAs(later).isNotNull();
      } finally {
        second.close();
      }
      assertThat(BootstrapDispatcher.installedDelegate()).isNull();
    }

    @Test
    @DisplayName("refuses while another copy's delegate is installed, and leaves it in place")
    void refusesAForeignDelegate() throws Exception {
      final Object foreign = new Object();
      BootstrapDispatcher.install(foreign, JdkInstrumentationInstaller.buildMethodHandles());
      try {
        assertThatThrownBy(() -> JdkInstrumentationInstaller.connectDispatcher(new ChaosRuntime()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("another class loader");
        assertThat(BootstrapDispatcher.installedDelegate()).isSameAs(foreign);
      } finally {
        BootstrapDispatcher.install(null, null);
      }
    }
  }
}
