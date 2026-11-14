package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BridgeVisibility")
class BridgeVisibilityTest {

  /** A loader whose view of the dispatcher class file is scripted; it counts the lookups. */
  private static final class ScriptedLoader extends ClassLoader {
    private final Supplier<@Nullable URL> dispatcherClassFile;
    private final AtomicInteger lookups = new AtomicInteger();

    ScriptedLoader(final Supplier<@Nullable URL> dispatcherClassFile) {
      super(null);
      this.dispatcherClassFile = dispatcherClassFile;
    }

    @Override
    public @Nullable URL getResource(final String name) {
      assertThat(name).isEqualTo(BridgeVisibility.DISPATCHER_CLASS_FILE);
      lookups.incrementAndGet();
      return dispatcherClassFile.get();
    }

    @Override
    public String toString() {
      return "scripted-loader@" + Integer.toHexString(System.identityHashCode(this));
    }
  }

  private static URL url(final String text) {
    try {
      return URI.create(text).toURL();
    } catch (final java.net.MalformedURLException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Test
  @DisplayName("the bootstrap class loader's classes are woven")
  void bootstrapLoaderIsWoven() {
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(null)).isFalse();
  }

  @Test
  @DisplayName("a loader that sees no dispatcher class file resolves it through its parents")
  void loaderWithoutCopyIsWoven() {
    final ScriptedLoader loader = new ScriptedLoader(() -> null);
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isFalse();
  }

  @Test
  @DisplayName("a loader that sees the class file the agent's loader sees is woven")
  void loaderSeeingTheAgentsCopyIsWoven() {
    final URL agentCopy =
        BridgeVisibility.class.getClassLoader().getResource(BridgeVisibility.DISPATCHER_CLASS_FILE);
    assertThat(agentCopy).as("the plain classes on the test class path").isNotNull();
    final ScriptedLoader loader = new ScriptedLoader(() -> url(agentCopy.toExternalForm()));
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isFalse();
  }

  @Test
  @DisplayName("a loader with a copy of its own is not woven, decided once and reported once")
  void loaderWithOwnCopyIsNotWoven() {
    final ScriptedLoader loader =
        new ScriptedLoader(() -> url("file:/webapp/WEB-INF/lib/agent.jar!/BootstrapDispatcher"));
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isTrue();
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isTrue();
    assertThat(loader.lookups).hasValue(1);
    assertThat(HookCoverage.failures())
        .filteredOn(failure -> failure.startsWith("class loader " + loader + ": "))
        .singleElement()
        .asString()
        .contains("a copy of the bootstrap bridge of its own")
        .contains("WEB-INF/lib/agent.jar");
  }

  @Test
  @DisplayName("a loader whose lookup fails is not woven and is reported")
  void failedLookupIsNotWoven() {
    final ScriptedLoader loader =
        new ScriptedLoader(
            () -> {
              throw new IllegalStateException("closed");
            });
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isTrue();
    assertThat(HookCoverage.failures())
        .anyMatch(
            failure ->
                failure.startsWith("class loader " + loader + ": ")
                    && failure.contains("could not be looked up")
                    && failure.contains("closed"));
  }

  @Test
  @DisplayName("after a reset a loader is decided again")
  void resetForgetsTheDecisions() {
    final ScriptedLoader loader = new ScriptedLoader(() -> null);
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isFalse();
    BridgeVisibility.reset();
    assertThat(BridgeVisibility.OWN_BRIDGE_COPY.matches(loader)).isFalse();
    assertThat(loader.lookups).hasValue(2);
  }
}
