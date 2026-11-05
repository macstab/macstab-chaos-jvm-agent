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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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
  @DisplayName("every handle is closed; the first failure is rethrown afterwards")
  void closePastFailures() {
    final List<String> closed = new ArrayList<>();
    final IllegalStateException first = new IllegalStateException("first");
    final List<ChaosActivationHandle> handles =
        new ArrayList<>(
            List.of(
                handle("a", closed, () -> first),
                handle("b", closed, () -> new IllegalStateException("second")),
                handle("c", closed, () -> null)));

    assertThatThrownBy(() -> WithChaosSupport.close(handles)).isSameAs(first);
    assertThat(closed).containsExactly("a", "b", "c");
    assertThat(handles).isEmpty();
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

  /** Records the scenarios activated on it; the handles it returns do nothing. */
  private static final class RecordingControlPlane implements ChaosControlPlane {
    private final List<String> activated = new ArrayList<>();

    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      activated.add(scenario.id());
      return handle(scenario.id(), new ArrayList<>(), () -> null);
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      throw new UnsupportedOperationException("not used");
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
  }

  @SuppressWarnings("unused")
  static final class Fixtures {
    @WithChaos(resource = "classpath:chaos/with-chaos-plan.json")
    void resource() {}
  }
}
