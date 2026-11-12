package com.macstab.chaos.jvm.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code @WithChaos} resources whatever class loader the thread has, and closing the
 * handles of a test past the ones that fail.
 */
@DisplayName("WithChaosSupport")
class WithChaosSupportTest {

  private final RecordingControlPlane controlPlane = new RecordingControlPlane();

  @Test
  @DisplayName("closing no handles does nothing")
  void closeNothing() {
    assertThatCode(() -> WithChaosSupport.close(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "every handle is closed; the first failure is rethrown afterwards with the others suppressed")
  void closePastFailures() {
    final List<String> closed = new ArrayList<>();
    final IllegalStateException first = new IllegalStateException("first");
    final IllegalStateException second = new IllegalStateException("second");
    final List<ChaosActivationHandle> handles =
        new ArrayList<>(
            List.of(
                handle("a", closed, () -> first),
                handle("b", closed, () -> second),
                handle("c", closed, () -> null)));

    assertThatThrownBy(() -> WithChaosSupport.close(handles)).isSameAs(first);
    assertThat(first.getSuppressed()).containsExactly(second);
    assertThat(closed).containsExactly("a", "b", "c");
    assertThat(handles).isEmpty();
  }

  @Test
  @DisplayName("invalid JSON is an IllegalArgumentException that names the line and column")
  void invalidJson() throws Exception {
    assertThatThrownBy(
            () ->
                WithChaosSupport.activate(
                    Fixtures.class.getDeclaredMethod("invalidJson"), controlPlane, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2")
        .hasMessageContaining("column")
        .hasCauseInstanceOf(ConfigLoadException.class);
    assertThat(controlPlane.activated).isEmpty();
  }

  @Test
  @DisplayName(
      "a handle failing to close during the rollback is suppressed; the activation failure is"
          + " thrown")
  void rollbackKeepsTheActivationFailure() throws Exception {
    final IllegalStateException closeFailure = new IllegalStateException("cannot close");
    controlPlane.closeFailure = closeFailure;

    assertThatThrownBy(
            () ->
                WithChaosSupport.activate(
                    Fixtures.class.getDeclaredMethod("validThenInvalid"), controlPlane, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("selector")
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(closeFailure));
    assertThat(controlPlane.activated).containsExactly("wc-valid");
    assertThat(controlPlane.closeAttempts).isEqualTo(1);
  }

  @Test
  @DisplayName("a plan keeps its name and observability, split by scope")
  void planKeepsMetadataAndObservability() throws Exception {
    final RecordingSession session = new RecordingSession();

    final List<ChaosActivationHandle> handles =
        WithChaosSupport.activate(
            Fixtures.class.getDeclaredMethod("mixedPlan"), controlPlane, session);

    assertThat(handles).hasSize(2);
    final ChaosPlan.Observability quiet = new ChaosPlan.Observability(false, false, false);
    assertThat(controlPlane.plans).hasSize(1);
    assertThat(controlPlane.plans.get(0).metadata().name()).isEqualTo("mixed");
    assertThat(controlPlane.plans.get(0).observability()).isEqualTo(quiet);
    assertThat(controlPlane.plans.get(0).scenarios())
        .extracting(ChaosScenario::id)
        .containsExactly("mixed-jvm-1", "mixed-jvm-2");
    assertThat(session.plans).hasSize(1);
    assertThat(session.plans.get(0).metadata().name()).isEqualTo("mixed");
    assertThat(session.plans.get(0).observability()).isEqualTo(quiet);
    assertThat(session.plans.get(0).scenarios())
        .extracting(ChaosScenario::id)
        .containsExactly("mixed-session");
  }

  @Test
  @DisplayName("without a context class loader, resources come from the testkit's loader")
  void noContextClassLoader() throws Exception {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    thread.setContextClassLoader(null);
    try {
      WithChaosSupport.activate(Fixtures.class.getDeclaredMethod("resource"), controlPlane, null);
    } finally {
      thread.setContextClassLoader(original);
    }

    assertThat(controlPlane.activated).containsExactly("wc-class-jvm");
    assertThat(controlPlane.plans.get(0).metadata().name()).isEqualTo("with-chaos-resource");
  }

  @Test
  @DisplayName("a resource that fails while being read is reported with its name and the cause")
  void unreadableResource() throws Exception {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    thread.setContextClassLoader(new FailingResourceLoader(original));
    try {
      assertThatThrownBy(
              () ->
                  WithChaosSupport.activate(
                      Fixtures.class.getDeclaredMethod("resource"), controlPlane, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("cannot read @WithChaos resource classpath:chaos/with-chaos-plan.json")
          .hasCauseInstanceOf(IOException.class);
    } finally {
      thread.setContextClassLoader(original);
    }
    assertThat(controlPlane.activated).isEmpty();
  }

  @Test
  @DisplayName("a gate without maxBlock is refused: nothing could release it")
  void unboundedGateRefused() throws Exception {
    assertThatThrownBy(
            () ->
                WithChaosSupport.activate(
                    Fixtures.class.getDeclaredMethod("unboundedGate"), controlPlane, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gate needs maxBlock")
        .hasCauseInstanceOf(ConfigLoadException.class);
    assertThat(controlPlane.activated).isEmpty();
  }

  private static ChaosActivationHandle handle(
      final String id, final List<String> closed, final Supplier<RuntimeException> failure) {
    return new ChaosActivationHandle() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public void start() {}

      @Override
      public void stop() {
        closed.add(id);
        final RuntimeException thrown = failure.get();
        if (thrown != null) {
          throw thrown;
        }
      }

      @Override
      public void release() {}

      @Override
      public ChaosDiagnostics.ScenarioState state() {
        return ChaosDiagnostics.ScenarioState.ACTIVE;
      }

      @Override
      public long matchedCount() {
        return 0L;
      }

      @Override
      public long appliedCount() {
        return 0L;
      }
    };
  }

  /** Finds every resource, but each one fails on its first read. */
  private static final class FailingResourceLoader extends ClassLoader {
    FailingResourceLoader(final ClassLoader parent) {
      super(parent);
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
      return new InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("disk gone");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length)
            throws IOException {
          throw new IOException("disk gone");
        }
      };
    }
  }

  /**
   * Records the plans and scenarios activated on it; the handles it returns fail to close with
   * {@link #closeFailure} when it is set.
   */
  private static final class RecordingControlPlane implements ChaosControlPlane {
    private final List<String> activated = new ArrayList<>();
    private final List<ChaosPlan> plans = new ArrayList<>();
    private @Nullable RuntimeException closeFailure;
    private int closeAttempts;

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      activated.add(scenario.id());
      return failingToClose(scenario.id());
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      plans.add(plan);
      plan.scenarios().forEach(scenario -> activated.add(scenario.id()));
      return failingToClose(plan.metadata().name());
    }

    private ChaosActivationHandle failingToClose(final String id) {
      return handle(
          id,
          new ArrayList<>(),
          () -> {
            closeAttempts++;
            return closeFailure;
          });
    }

    @Override
    public ChaosSession openSession(final String displayName) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void addEventListener(final ChaosEventListener listener) {}

    @Override
    public boolean removeEventListener(final ChaosEventListener listener) {
      return false;
    }

    @Override
    public void close() {}

    @Override
    public ChaosSession.ScopeBinding bindSession(final String sessionId) {
      return () -> {};
    }
  }

  /** Records the plans activated in it. */
  private static final class RecordingSession implements ChaosSession {
    private final List<ChaosPlan> plans = new ArrayList<>();

    @Override
    public String id() {
      return "recording";
    }

    @Override
    public String displayName() {
      return "recording";
    }

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      throw new UnsupportedOperationException("@WithChaos activates plans");
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      plans.add(plan);
      return handle(plan.metadata().name(), new ArrayList<>(), () -> null);
    }

    @Override
    public ChaosSession.ScopeBinding bind() {
      return () -> {};
    }

    @Override
    public Runnable wrap(final Runnable runnable) {
      return runnable;
    }

    @Override
    public <T> Callable<T> wrap(final Callable<T> callable) {
      return callable;
    }

    @Override
    public void close() {}
  }

  @SuppressWarnings("unused")
  static final class Fixtures {
    @WithChaos(resource = "classpath:chaos/with-chaos-plan.json")
    void resource() {}

    @WithChaos(
        "{\"id\": \"g\", \"selector\": {\"type\": \"executor\", \"operations\":"
            + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": {\"type\": \"gate\"}}")
    void unboundedGate() {}

    @WithChaos("{\"id\": \"broken\",\n \"selector\": }")
    void invalidJson() {}

    @WithChaos(
        "{\"id\": \"wc-valid\", \"selector\": {\"type\": \"executor\", \"operations\":"
            + " [\"EXECUTOR_SUBMIT\"]}, \"effect\": {\"type\": \"delay\", \"minDelay\":"
            + " \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}}")
    @WithChaos("{\"id\": \"wc-invalid\"}")
    void validThenInvalid() {}

    @WithChaos(
        """
        {"metadata": {"name": "mixed"},
         "observability": {"jmxEnabled": false, "structuredLoggingEnabled": false,
                           "debugDumpOnStart": false},
         "scenarios": [
           {"id": "mixed-jvm-1", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}},
           {"id": "mixed-session", "scope": "SESSION",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}},
           {"id": "mixed-jvm-2", "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
            "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
        """)
    void mixedPlan() {}
  }
}
