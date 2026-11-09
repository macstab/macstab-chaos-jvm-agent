package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import net.bytebuddy.dynamic.Nexus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdkInstrumentationInstaller")
class JdkInstrumentationInstallerTest {

  @Test
  @DisplayName("a failed type is formatted for its record only when the log level drops the line")
  void failedTypeIsNotFormattedForADroppedLogLine() {
    final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger("com.macstab.chaos.jvm");
    final java.util.logging.Level previous = logger.getLevel();
    final java.util.concurrent.atomic.AtomicInteger formatted =
        new java.util.concurrent.atomic.AtomicInteger();
    final Throwable failure =
        new IllegalStateException() {
          // Throwable.toString reads the message: each formatting of the failure counts.
          @Override
          public String getMessage() {
            formatted.incrementAndGet();
            return "probe failure";
          }
        };
    logger.setLevel(java.util.logging.Level.OFF);
    try {
      JdkInstrumentationInstaller.errorListener()
          .onError(
              "com.example.FormattedOnce",
              ClassLoader.getSystemClassLoader(),
              net.bytebuddy.utility.JavaModule.ofType(Object.class),
              false,
              failure);
    } finally {
      logger.setLevel(previous);
    }

    assertThat(formatted).as("formatted for the hookFailures record only").hasValue(1);
  }

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

  @Test
  @DisplayName("building an agent builder keeps a Nexus switch the JVM was started with")
  void agentBuilderKeepsTheNexusSwitch() {
    final String before = System.getProperty(Nexus.PROPERTY);
    System.setProperty(Nexus.PROPERTY, "false");
    try {
      JdkInstrumentationInstaller.newAgentBuilder();

      assertThat(System.getProperty(Nexus.PROPERTY)).isEqualTo("false");
    } finally {
      if (before == null) {
        System.clearProperty(Nexus.PROPERTY);
      } else {
        System.setProperty(Nexus.PROPERTY, before);
      }
    }
  }
}
