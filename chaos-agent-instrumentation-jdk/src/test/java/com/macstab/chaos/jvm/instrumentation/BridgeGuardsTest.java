package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.OperationTags;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The two guards every bridge entry point opens with, over every entry point: without an installed
 * agent, and inside the agent's own work (L3), a call passes through unchanged — it returns its own
 * input, the length it was given, or {@code false}/{@code null}, and throws nothing. The same holds
 * with the agent connected but no scenario active, and before the delegate's handles are published;
 * a failure of the delegate reaches the advice as it was thrown.
 */
@DisplayName("Bridge entry-point guards")
class BridgeGuardsTest {

  /**
   * Infrastructure, and the helpers advice calls once a decision is made (building the failure,
   * flipping the bit, reading the real clock): not guarded interception points.
   */
  private static final Set<String> NOT_ENTRY_POINTS =
      Set.of(
          "install",
          "installedDelegate",
          "claimRevision",
          "isDepthThreadLocal",
          "beginInternalCall",
          "endInternalCall",
          "enterScope",
          "exitScope",
          "inScope",
          "consumeScope",
          // Whether advice may skip reading its target; guarded on its own (idleAnswers).
          "idle",
          "tornWriteFailure",
          "corruptBytes",
          "corruptByte",
          "corruptBuffer",
          "corruptionSeed",
          "isArchive",
          // What this JDK's Socket.connect closes on a failure; a constant of the JDK.
          "failedConnectClosesSocket",
          // End a stall the bridge itself runs, or drop a wakeup a real select consumed; no
          // delegate.
          "selectorWoken",
          "afterNioSelect",
          "currentTimeMillis",
          "nanoTime");

  /** Entry points whose {@code true} means "proceed", so that is what they return unchanged. */
  private static final Set<String> PROCEED_IS_TRUE = Set.of("beforeScheduledTick");

  @Test
  @DisplayName("with no agent installed every entry point passes the call through")
  void passThroughWithoutAgent() throws Exception {
    final Object delegate = BootstrapDispatcher.installedDelegate();
    assertThat(delegate).as("no bridge installed in this test JVM").isNull();
    assertPassThrough("without an agent");
  }

  @Test
  // Same thread (the default): the guard is the DEPTH ThreadLocal of the thread that raised it.
  @Timeout(10)
  @DisplayName("inside the agent's own work every entry point passes the call through (L3)")
  void passThroughInsideAgentWork() throws Exception {
    final ChaosRuntime runtime = new ChaosRuntime();
    try (AutoCloseable connected = JdkInstrumentationInstaller.connectDispatcher(runtime)) {
      // A scenario on every operation, with the effect it accepts first of a reject, an errno, a
      // 30 s delay and a clock skew: an entry point that evaluated would throw, return something
      // else, or wait.
      final EnumSet<OperationType> covered = EnumSet.noneOf(OperationType.class);
      for (final Map.Entry<OperationType, List<ChaosSelector>> operation :
          selectorsByOperation().entrySet()) {
        if (activateFor(runtime, operation.getKey(), operation.getValue())) {
          covered.add(operation.getKey());
        }
      }
      final Set<OperationType> without = EnumSet.complementOf(covered);
      without.remove(OperationType.LIFECYCLE);
      assertThat(without).as("operations without an active scenario").isEmpty();
      BootstrapDispatcher.beginInternalCall();
      try {
        assertPassThrough("inside agent work");
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("with the agent connected and no scenario every entry point passes the call through")
  void passThroughWithoutScenario() throws Throwable {
    final ChaosRuntime runtime = new ChaosRuntime();
    try (AutoCloseable connected = JdkInstrumentationInstaller.connectDispatcher(runtime)) {
      assertPassThrough("without a scenario");
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName(
      "idle is true wherever a call passes through, and false only when it may be a target")
  void idleAnswers() throws Throwable {
    assertThat(BootstrapDispatcher.idle(OperationTags.MESSAGE_RECEIVE)).as("no agent").isTrue();
    try {
      BootstrapDispatcher.install(new Object(), null);
      assertThat(BootstrapDispatcher.idle(OperationTags.MESSAGE_RECEIVE)).as("no handles").isTrue();
      BootstrapDispatcher.install(new Object(), failingHandles(new Exception("delegate failure")));
      assertThat(BootstrapDispatcher.idle(OperationTags.MESSAGE_RECEIVE))
          .as("a delegate failure: evaluate as before")
          .isFalse();
    } finally {
      // The delegate is JVM-global: a failure must not leave it installed for later tests.
      BootstrapDispatcher.install(null, null);
    }

    final ChaosRuntime runtime = new ChaosRuntime();
    try (AutoCloseable connected = JdkInstrumentationInstaller.connectDispatcher(runtime)) {
      assertThat(BootstrapDispatcher.idle(OperationTags.SCHEDULE_SUBMIT_PERIODIC)).isTrue();
      runtime.activate(
          ChaosScenario.builder("idle-schedule")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofSeconds(30)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      assertThat(BootstrapDispatcher.idle(OperationTags.SCHEDULE_SUBMIT_PERIODIC))
          .as("a periodic submission is a SCHEDULE_SUBMIT")
          .isFalse();
      assertThat(BootstrapDispatcher.idle(OperationTags.MESSAGE_RECEIVE)).isTrue();
      BootstrapDispatcher.beginInternalCall();
      try {
        assertThat(BootstrapDispatcher.idle(OperationTags.SCHEDULE_SUBMIT))
            .as("agent work")
            .isTrue();
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("a delegate published before its method handles passes every call through")
  void passThroughWithoutHandles() throws Exception {
    BootstrapDispatcher.install(new Object(), null);
    try {
      assertPassThrough("without method handles");
    } finally {
      BootstrapDispatcher.install(null, null);
    }
  }

  @Test
  @DisplayName("a failure of the delegate reaches the advice as thrown, unwrapped")
  void delegateFailureReachesAdvice() throws Exception {
    final Exception failure = new Exception("delegate failure");
    BootstrapDispatcher.install(new Object(), failingHandles(failure));
    try {
      final List<String> violations = new ArrayList<>();
      for (final Method method : entryPoints()) {
        final Object[] arguments = argumentsFor(method);
        final boolean swallows = SWALLOW_FAILURE.contains(method.getName());
        try {
          final Object result = method.invoke(null, arguments);
          if (!swallows) {
            violations.add(method.getName() + " returned " + result);
          } else if (!unchanged(method.getReturnType(), result, arguments)) {
            violations.add(method.getName() + " swallowed the failure but returned " + result);
          }
        } catch (final InvocationTargetException thrown) {
          if (swallows || thrown.getCause() != failure) {
            violations.add(method.getName() + " threw " + thrown.getCause());
          }
        }
      }
      assertThat(violations).isEmpty();
      // The corruption still flips one bit, on a seed of its own.
      assertThat(Integer.bitCount(BootstrapDispatcher.corruptByte(0))).isEqualTo(1);
    } finally {
      BootstrapDispatcher.install(null, null);
    }
  }

  /**
   * Entry points that must never fail the call they intercept: a failure of the delegate makes them
   * pass the call through.
   */
  private static final Set<String> SWALLOW_FAILURE =
      Set.of("currentSessionId", "sessionHeaderFor", "afterProcessStart");

  /** Arguments that make an entry point reach its delegate, where the samples do not. */
  private static Object[] argumentsFor(final Method method) throws Exception {
    final Object[] arguments = arguments(method.getParameterTypes());
    final Class<?>[] types = method.getParameterTypes();
    if (method.getName().startsWith("beforeFile")) {
      arguments[0] = "FILE_IO_READ";
    }
    for (int i = 0; i < types.length; i++) {
      if (types[i] == int.class
          && i > 0
          && types[i - 1] == ByteBuffer[].class) { // offset, then length, of a buffer array
        arguments[i] = 0;
        arguments[i + 1] = 1;
        break;
      }
    }
    return arguments;
  }

  /** Method handles of the delegate's shape that all throw {@code failure}. */
  private static MethodHandle[] failingHandles(final Throwable failure) throws Exception {
    final MethodHandle[] real = JdkInstrumentationInstaller.buildMethodHandles();
    final MethodHandle[] failing = new MethodHandle[real.length];
    for (int i = 0; i < real.length; i++) {
      final MethodType type = real[i].type().changeParameterType(0, Object.class);
      failing[i] =
          MethodHandles.dropArguments(
              MethodHandles.throwException(type.returnType(), Throwable.class).bindTo(failure),
              0,
              type.parameterList());
    }
    return failing;
  }

  private static List<Method> entryPoints() {
    final List<Method> entryPoints = new ArrayList<>();
    for (final Method method : BootstrapDispatcher.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())
          && Modifier.isStatic(method.getModifiers())
          && !NOT_ENTRY_POINTS.contains(method.getName())) {
        entryPoints.add(method);
      }
    }
    return entryPoints;
  }

  private static void assertPassThrough(final String situation) throws Exception {
    final List<String> violations = new ArrayList<>();
    int checked = 0;
    for (final Method method : entryPoints()) {
      checked++;
      final Object[] arguments = argumentsFor(method);
      final Object result;
      try {
        result = method.invoke(null, arguments);
      } catch (final InvocationTargetException thrown) {
        violations.add(method.getName() + " threw " + thrown.getCause());
        continue;
      }
      final boolean unchanged =
          PROCEED_IS_TRUE.contains(method.getName())
              ? Boolean.TRUE.equals(result)
              : unchanged(method.getReturnType(), result, arguments);
      if (!unchanged) {
        violations.add(method.getName() + " returned " + result);
      }
    }
    assertThat(checked).as("entry points checked").isGreaterThan(60);
    assertThat(violations).as(situation).isEmpty();
  }

  /** What an entry point returns when it changes nothing. */
  private static boolean unchanged(final Class<?> type, final Object result, final Object[] args) {
    if (type == void.class) {
      return true;
    }
    if (type == boolean.class) {
      return Boolean.FALSE.equals(result);
    }
    if (type == int.class) {
      return Integer.valueOf(SEVEN).equals(result) || Integer.valueOf(0).equals(result);
    }
    if (type == long.class) {
      return Long.valueOf(SEVEN).equals(result);
    }
    return result == null || Arrays.stream(args).anyMatch(arg -> arg == result);
  }

  private static final int SEVEN = 7;

  private static Object[] arguments(final Class<?>[] types) throws Exception {
    final Object[] arguments = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      arguments[i] = sample(types[i]);
    }
    return arguments;
  }

  private static Object sample(final Class<?> type) throws Exception {
    if (type == int.class) return SEVEN;
    if (type == long.class) return (long) SEVEN;
    if (type == boolean.class) return false;
    if (type == String.class) return "SOCKET_READ";
    if (type == Class.class) return Object.class;
    if (type == ClassLoader.class) return BridgeGuardsTest.class.getClassLoader();
    if (type == Runnable.class) return (Runnable) () -> {};
    if (type == java.util.concurrent.Callable.class) {
      return (java.util.concurrent.Callable<Object>) () -> null;
    }
    if (type == Thread.class) return new Thread(() -> {});
    if (type == Throwable.class) return new RuntimeException("sample");
    if (type == CompletableFuture.class) return new CompletableFuture<>();
    if (type == ForkJoinTask.class) return ForkJoinTask.adapt(() -> {});
    if (type == java.net.URL.class) return java.net.URI.create("file:/sample").toURL();
    if (type == InetAddress.class) return InetAddress.getLoopbackAddress();
    if (type == InetAddress[].class) return new InetAddress[] {InetAddress.getLoopbackAddress()};
    if (type == ByteBuffer.class) return ByteBuffer.allocate(SEVEN);
    if (type == ByteBuffer[].class) return new ByteBuffer[] {ByteBuffer.allocate(SEVEN)};
    if (type == byte[].class) return new byte[SEVEN];
    if (type == java.net.DatagramPacket.class)
      return new java.net.DatagramPacket(new byte[SEVEN], SEVEN);
    if (type == Instant.class) return Instant.EPOCH;
    if (type == LocalDateTime.class) return LocalDateTime.of(2026, 1, 1, 0, 0);
    if (type == ZonedDateTime.class)
      return ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    if (type == java.time.ZoneId.class) return ZoneOffset.UTC;
    if (type == java.lang.invoke.MethodHandle[].class) return new java.lang.invoke.MethodHandle[0];
    return new Object();
  }

  /**
   * Activates a scenario on {@code operation} with the first effect one of its selectors accepts: a
   * reject, else an errno, else a 30 s delay.
   *
   * @return whether one was activated
   */
  private static boolean activateFor(
      final ChaosRuntime runtime,
      final OperationType operation,
      final List<ChaosSelector> selectors) {
    final List<ChaosEffect> effects = new ArrayList<>();
    effects.add(ChaosEffect.reject("guard"));
    for (final ChaosEffect.Errno errno : ChaosEffect.Errno.values()) {
      effects.add(ChaosEffect.errno(errno));
    }
    effects.add(ChaosEffect.delay(Duration.ofSeconds(30)));
    // The clock reads take only a skew.
    effects.add(ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED));
    for (final ChaosEffect effect : effects) {
      for (final ChaosSelector selector : selectors) {
        try {
          runtime.activate(
              ChaosScenario.builder("guard-" + operation)
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(selector)
                  .effect(effect)
                  .activationPolicy(ActivationPolicy.always())
                  .build());
          return true;
        } catch (final RuntimeException refused) {
          // this selector does not take this effect; try the next
        }
      }
    }
    return false;
  }

  // Copied from EffectReachesTheCallTest (chaos-agent-core): the modules share no test fixtures.

  /** For each operation, the selectors (with no filter) that select only that operation. */
  private static Map<OperationType, List<ChaosSelector>> selectorsByOperation() {
    final Map<OperationType, List<ChaosSelector>> selectors = new EnumMap<>(OperationType.class);
    for (final Class<?> type : ChaosSelector.class.getPermittedSubclasses()) {
      for (final OperationType operation : OperationType.values()) {
        ChaosSelector selector = unfiltered(type, operation, false);
        if (selector == null) {
          // A method selector needs one pattern that is not ANY: it names this test class.
          selector = unfiltered(type, operation, true);
        }
        if (selector != null) {
          selectors.computeIfAbsent(operation, any -> new ArrayList<>()).add(selector);
        }
      }
    }
    return selectors;
  }

  private static @Nullable ChaosSelector unfiltered(
      final Class<?> type, final OperationType operation, final boolean nameProbe) {
    final RecordComponent[] components = type.getRecordComponents();
    if (components == null
        || Arrays.stream(components).noneMatch(c -> c.getName().equals("operations"))) {
      return null;
    }
    final Object[] arguments = new Object[components.length];
    final Class<?>[] parameterTypes = new Class<?>[components.length];
    boolean named = !nameProbe;
    for (int index = 0; index < components.length; index++) {
      parameterTypes[index] = components[index].getType();
      if (components[index].getName().equals("operations")) {
        arguments[index] = EnumSet.of(operation);
      } else if (parameterTypes[index] == NamePattern.class) {
        arguments[index] =
            named ? NamePattern.any() : NamePattern.exact(BridgeGuardsTest.class.getName());
        named = true;
      } else {
        arguments[index] = defaultValue(parameterTypes[index]);
      }
    }
    try {
      final Constructor<?> canonical = type.getDeclaredConstructor(parameterTypes);
      canonical.setAccessible(true);
      return (ChaosSelector) canonical.newInstance(arguments);
    } catch (final InvocationTargetException rejected) {
      return null;
    } catch (final ReflectiveOperationException unreachable) {
      throw new IllegalStateException(type.getName(), unreachable);
    }
  }

  private static @Nullable Object defaultValue(final Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
