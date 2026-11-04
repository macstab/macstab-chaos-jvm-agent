package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two guards every bridge entry point opens with, over every entry point: without an installed
 * agent, and inside the agent's own work (L3), a call passes through unchanged — it returns its own
 * input, the length it was given, or {@code false}/{@code null}, and throws nothing.
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
          "depthThreadLocal",
          "isDepthThreadLocal",
          "beginInternalCall",
          "endInternalCall",
          "enterScope",
          "exitScope",
          "tornWriteFailure",
          "corruptBytes",
          "corruptByte",
          "corruptionSeed",
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
  @DisplayName("inside the agent's own work every entry point passes the call through (L3)")
  @org.junit.jupiter.api.Tag("law-L3")
  void passThroughInsideAgentWork() throws Exception {
    final ChaosRuntime runtime = new ChaosRuntime();
    try (AutoCloseable connected = JdkInstrumentationInstaller.connectDispatcher(runtime)) {
      // A reject on every operation: any entry point that evaluated would throw.
      for (final OperationType operation : EnumSet.allOf(OperationType.class)) {
        activateRejectFor(runtime, operation);
      }
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

  private static void assertPassThrough(final String situation) throws Exception {
    final List<String> violations = new ArrayList<>();
    int checked = 0;
    for (final Method method : BootstrapDispatcher.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers())
          || !Modifier.isStatic(method.getModifiers())
          || NOT_ENTRY_POINTS.contains(method.getName())) {
        continue;
      }
      checked++;
      final Object[] arguments = arguments(method.getParameterTypes());
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

  private static void activateRejectFor(final ChaosRuntime runtime, final OperationType operation) {
    try {
      runtime.activate(
          ChaosScenario.builder("reject-" + operation)
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.jvmRuntime(Set.of(operation)))
              .effect(ChaosEffect.delay(Duration.ofSeconds(30)))
              .activationPolicy(ActivationPolicy.always())
              .build());
    } catch (final RuntimeException notApplicable) {
      // not every operation takes this selector; the others are covered by the depth guard alone
    }
  }
}
