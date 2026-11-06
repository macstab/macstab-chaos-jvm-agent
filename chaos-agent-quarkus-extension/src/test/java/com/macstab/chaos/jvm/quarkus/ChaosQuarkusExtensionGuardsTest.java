package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEventListener;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;

/**
 * The extension driven through contexts JUnit itself never hands it: no test class or method, no
 * class-level setup, state only in a parent context. Each callback does what it can and stops where
 * the state it needs is missing.
 */
@SuppressWarnings("removal")
@DisplayName("ChaosQuarkusExtension guards")
class ChaosQuarkusExtensionGuardsTest {

  private final ChaosQuarkusExtension extension = new ChaosQuarkusExtension();
  private final RecordingControlPlane controlPlane = new RecordingControlPlane();
  private final RecordingSession session = new RecordingSession();

  @Test
  @DisplayName("a class context without a test class sets up a session and activates nothing")
  void beforeAllWithoutTestClass() {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext context = context(values, null, null, null);

    extension.beforeAll(context);
    try {
      assertThat(values).containsKeys(ChaosControlPlane.class, ChaosSession.class);
      assertThat(values.get(ChaosControlPlane.class))
          .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class);
    } finally {
      extension.afterAll(context);
    }
    assertThat(values).doesNotContainKeys(ChaosSession.class);
  }

  @Test
  @DisplayName("a JVM scenario activated through the injected control plane ends with the class")
  void injectedActivationEndsWithTheClass() {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext context = context(values, null, null, null);
    extension.beforeAll(context);
    final ChaosControlPlane injected = (ChaosControlPlane) values.get(ChaosControlPlane.class);
    try {
      injected.activate(
          com.macstab.chaos.jvm.api.ChaosScenario.builder("injected-jvm")
              .selector(
                  com.macstab.chaos.jvm.api.ChaosSelector.executor(
                      java.util.Set.of(com.macstab.chaos.jvm.api.OperationType.EXECUTOR_SUBMIT)))
              .effect(com.macstab.chaos.jvm.api.ChaosEffect.delay(java.time.Duration.ofMillis(1)))
              .build());
    } finally {
      extension.afterAll(context);
    }

    assertThat(ChaosPlatform.current().diagnostics().scenario("injected-jvm")).isEmpty();
  }

  @Test
  @DisplayName("before each test, nothing happens without a test method or without a session")
  void beforeEachWithoutState() throws Exception {
    final Map<Object, Object> withoutMethod = new HashMap<>();
    extension.beforeEach(context(withoutMethod, null, null, null));
    assertThat(withoutMethod).isEmpty();

    final Map<Object, Object> withoutSession = new HashMap<>();
    withoutSession.put(ChaosControlPlane.class, controlPlane);
    extension.beforeEach(context(withoutSession, null, fixture("sessionScoped"), null));
    assertThat(withoutSession).containsOnlyKeys(ChaosControlPlane.class);

    final Map<Object, Object> withoutControlPlane = new HashMap<>();
    withoutControlPlane.put(ChaosSession.class, session);
    extension.beforeEach(context(withoutControlPlane, null, fixture("sessionScoped"), null));
    assertThat(withoutControlPlane).containsOnlyKeys(ChaosSession.class);
    assertThat(session.activated).isEmpty();
  }

  @Test
  @DisplayName("state held by a parent context is found; session scenarios go to the session")
  void stateFromParentContext() throws Exception {
    final ExtensionContext parent = classContext();
    final ExtensionContext method =
        context(new HashMap<>(), null, fixture("sessionScoped"), parent);

    extension.beforeEach(method);

    assertThat(session.activated).containsExactly("guard-session");
    assertThat(controlPlane.activated).isEmpty();
  }

  @Test
  @DisplayName("a method's JVM handle failing to close is swallowed after the test")
  void failingMethodHandle() throws Exception {
    final ExtensionContext method =
        context(new HashMap<>(), null, fixture("jvmScoped"), classContext());
    extension.beforeEach(method);

    assertThatCode(() -> extension.afterEach(method)).doesNotThrowAnyException();
    assertThat(controlPlane.activated).containsExactly("guard-jvm");
    assertThat(controlPlane.closeAttempts).isEqualTo(1);
  }

  @Test
  @DisplayName("after each test and after all, nothing is torn down that was never set up")
  void teardownWithoutSetup() {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext context = context(values, null, null, null);

    assertThatCode(
            () -> {
              extension.afterEach(context);
              extension.afterAll(context);
            })
        .doesNotThrowAnyException();
    assertThat(values).isEmpty();
  }

  @Test
  @DisplayName("resolves only sessions and control planes, and says when none is available")
  void parameters() throws Exception {
    final ExtensionContext context =
        context(new HashMap<>(), null, null, context(new HashMap<>(), null, null, null));

    assertThat(extension.supportsParameter(parameter(ChaosSession.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(ChaosControlPlane.class), context)).isTrue();
    assertThat(extension.supportsParameter(parameter(String.class), context)).isFalse();
    assertThatThrownBy(() -> extension.resolveParameter(parameter(ChaosSession.class), context))
        .isInstanceOf(ParameterResolutionException.class)
        .hasMessageStartingWith("ChaosQuarkusExtension: no ChaosSession available");
  }

  @Test
  @DisplayName("a blank selector or effect is rejected")
  void blankIdentifiers() {
    assertThatThrownBy(() -> ChaosQuarkusExtension.toScenario(annotation("blankSelector")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("selector must be non-blank");
    assertThatThrownBy(() -> ChaosQuarkusExtension.toScenario(annotation("blankEffect")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("effect must be non-blank");
  }

  @Test
  @DisplayName("the default executor operations are those of the executor identifier")
  void defaultExecutorOperations() {
    assertThat(
            ChaosSelector.executor(ChaosQuarkusExtension.defaultExecutorOperations()).operations())
        .isEqualTo(
            ((ChaosSelector.ExecutorSelector)
                    ChaosQuarkusExtension.toScenario(annotation("sessionScoped")).selector())
                .operations());
  }

  private ExtensionContext classContext() {
    final Map<Object, Object> values = new HashMap<>();
    values.put(ChaosControlPlane.class, controlPlane);
    values.put(ChaosSession.class, session);
    return context(values, null, null, null);
  }

  private static Method fixture(final String name) throws NoSuchMethodException {
    return Fixtures.class.getDeclaredMethod(name);
  }

  private static ChaosScenario annotation(final String name) {
    try {
      return fixture(name).getAnnotation(ChaosScenario.class);
    } catch (final NoSuchMethodException missing) {
      throw new IllegalStateException(missing);
    }
  }

  @SuppressWarnings("unused")
  private static void target(
      final ChaosSession session, final ChaosControlPlane plane, final String other) {}

  private static ParameterContext parameter(final Class<?> type) throws Exception {
    final Parameter parameter =
        java.util.Arrays.stream(
                ChaosQuarkusExtensionGuardsTest.class
                    .getDeclaredMethod(
                        "target", ChaosSession.class, ChaosControlPlane.class, String.class)
                    .getParameters())
            .filter(candidate -> candidate.getType() == type)
            .findFirst()
            .orElseThrow();
    return (ParameterContext)
        Proxy.newProxyInstance(
            ParameterContext.class.getClassLoader(),
            new Class<?>[] {ParameterContext.class},
            (self, method, args) ->
                method.getName().equals("getParameter")
                    ? parameter
                    : method.isDefault()
                        ? java.lang.reflect.InvocationHandler.invokeDefault(self, method, args)
                        : null);
  }

  /**
   * A context whose store holds {@code values} (without the hierarchical lookup of JUnit's own
   * stores), with the given test class, test method and parent, each of which may be absent.
   */
  private static ExtensionContext context(
      final Map<Object, Object> values,
      final Class<?> testClass,
      final Method testMethod,
      final ExtensionContext parent) {
    final ExtensionContext.Store store =
        (ExtensionContext.Store)
            Proxy.newProxyInstance(
                ExtensionContext.Store.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.Store.class},
                (self, method, args) ->
                    switch (method.getName()) {
                      case "get" -> values.get(args[0]);
                      case "put" -> values.put(args[0], args[1]);
                      case "remove" -> values.remove(args[0]);
                      default -> null;
                    });
    return (ExtensionContext)
        Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "getStore" -> store;
                  case "getParent" -> Optional.ofNullable(parent);
                  case "getTestClass" -> Optional.ofNullable(testClass);
                  case "getTestMethod" -> Optional.ofNullable(testMethod);
                  case "getDisplayName" -> "guards";
                  default -> null;
                });
  }

  /** Records what is activated on it; its handles fail to close. */
  private static final class RecordingControlPlane implements ChaosControlPlane {
    private final List<String> activated = new ArrayList<>();
    private int closeAttempts;

    @Override
    public ChaosActivationHandle activate(final com.macstab.chaos.jvm.api.ChaosScenario scenario) {
      activated.add(scenario.id());
      return new ChaosActivationHandle() {
        @Override
        public String id() {
          return scenario.id();
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
          closeAttempts++;
          throw new IllegalStateException("cannot stop " + scenario.id());
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

    @Override
    public ChaosSession.ScopeBinding bindSession(final String sessionId) {
      return () -> {};
    }
  }

  /** Records the scenarios activated on it. */
  private static final class RecordingSession implements ChaosSession {
    private final List<String> activated = new ArrayList<>();

    @Override
    public String id() {
      return "guard-session";
    }

    @Override
    public String displayName() {
      return "guards";
    }

    @Override
    public ChaosActivationHandle activate(final com.macstab.chaos.jvm.api.ChaosScenario scenario) {
      activated.add(scenario.id());
      return null;
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public ScopeBinding bind() {
      return () -> {};
    }

    @Override
    public Runnable wrap(final Runnable runnable) {
      return runnable;
    }

    @Override
    public <T> java.util.concurrent.Callable<T> wrap(
        final java.util.concurrent.Callable<T> callable) {
      return callable;
    }

    @Override
    public void close() {}
  }

  @SuppressWarnings("unused")
  static final class Fixtures {
    @ChaosScenario(
        id = "guard-session",
        selector = "executor",
        effect = "delay:PT0.001S",
        scope = "SESSION")
    void sessionScoped() {}

    @ChaosScenario(id = "guard-jvm", selector = "executor", effect = "delay:PT0.001S")
    void jvmScoped() {}

    @ChaosScenario(id = "blank-selector", selector = " ", effect = "suppress")
    void blankSelector() {}

    @ChaosScenario(id = "blank-effect", selector = "executor", effect = " ")
    void blankEffect() {}
  }
}
