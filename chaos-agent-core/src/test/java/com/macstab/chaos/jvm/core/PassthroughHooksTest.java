package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code ChaosRuntime} entry points the advice calls pass a call through untouched while no
 * scenario selects it — no event, no counter, no delay — and the same call is evaluated once a
 * scenario selects its operation. The pair shows the hook is wired: a hook that did nothing would
 * pass the first half and fail the second.
 */
@DisplayName("A runtime hook without a scenario")
class PassthroughHooksTest {

  private static final Duration DELAY = Duration.ofMillis(30);

  /** One runtime entry point: the call, and the operation and selector that reach it. */
  @FunctionalInterface
  private interface Hook {
    void call(ChaosRuntime runtime) throws Throwable;
  }

  private static Arguments hook(
      final String name,
      final OperationType operation,
      final ChaosSelector selector,
      final Hook hook) {
    return Arguments.of(name, operation, selector, hook);
  }

  private static ChaosSelector runtimeOp(final OperationType operation) {
    return ChaosSelector.jvmRuntime(Set.of(operation));
  }

  private static ChaosSelector socketOp(final OperationType operation) {
    return new ChaosSelector.NetworkSelector(Set.of(operation), NamePattern.any());
  }

  static Stream<Arguments> hooks() {
    return Stream.of(
        hook(
            "exit request",
            OperationType.SYSTEM_EXIT_REQUEST,
            runtimeOp(OperationType.SYSTEM_EXIT_REQUEST),
            runtime -> runtime.beforeExitRequest(0)),
        hook(
            "object deserialize",
            OperationType.OBJECT_DESERIALIZE,
            runtimeOp(OperationType.OBJECT_DESERIALIZE),
            runtime -> runtime.beforeObjectDeserialize(null)),
        hook(
            "object serialize",
            OperationType.OBJECT_SERIALIZE,
            runtimeOp(OperationType.OBJECT_SERIALIZE),
            runtime -> runtime.beforeObjectSerialize(null, null)),
        hook(
            "zip inflate",
            OperationType.ZIP_INFLATE,
            runtimeOp(OperationType.ZIP_INFLATE),
            ChaosRuntime::beforeZipInflate),
        hook(
            "zip deflate",
            OperationType.ZIP_DEFLATE,
            runtimeOp(OperationType.ZIP_DEFLATE),
            ChaosRuntime::beforeZipDeflate),
        hook(
            "direct buffer allocate",
            OperationType.DIRECT_BUFFER_ALLOCATE,
            runtimeOp(OperationType.DIRECT_BUFFER_ALLOCATE),
            runtime -> runtime.beforeDirectBufferAllocate(1024)),
        hook(
            "reflection invoke",
            OperationType.REFLECTION_INVOKE,
            runtimeOp(OperationType.REFLECTION_INVOKE),
            runtime -> runtime.beforeReflectionInvoke(null, null)),
        hook(
            "socket connect",
            OperationType.SOCKET_CONNECT,
            socketOp(OperationType.SOCKET_CONNECT),
            runtime -> runtime.beforeSocketConnect(null, null, 0)),
        hook(
            "class define",
            OperationType.CLASS_DEFINE,
            ChaosSelector.classLoading(Set.of(OperationType.CLASS_DEFINE), NamePattern.any()),
            runtime -> runtime.beforeClassDefine(null, "com.example.Foo")),
        hook(
            "socket accept",
            OperationType.SOCKET_ACCEPT,
            socketOp(OperationType.SOCKET_ACCEPT),
            runtime -> runtime.beforeSocketAccept(null)),
        hook(
            "socket read",
            OperationType.SOCKET_READ,
            socketOp(OperationType.SOCKET_READ),
            runtime -> runtime.beforeSocketRead(null, InvocationContext.UNKNOWN, null, 0, 0)),
        hook(
            "socket write",
            OperationType.SOCKET_WRITE,
            socketOp(OperationType.SOCKET_WRITE),
            runtime -> runtime.beforeSocketWrite(null, 64, null, 0)),
        hook(
            "socket close",
            OperationType.SOCKET_CLOSE,
            socketOp(OperationType.SOCKET_CLOSE),
            runtime -> runtime.beforeSocketClose(null)),
        hook(
            "JNDI lookup",
            OperationType.JNDI_LOOKUP,
            runtimeOp(OperationType.JNDI_LOOKUP),
            runtime -> runtime.beforeJndiLookup(null, "java:comp/env/test")),
        hook(
            "JMX getAttribute",
            OperationType.JMX_GET_ATTR,
            runtimeOp(OperationType.JMX_GET_ATTR),
            runtime -> runtime.beforeJmxGetAttr(null, null, "VmName")),
        hook(
            "JMX invoke",
            OperationType.JMX_INVOKE,
            runtimeOp(OperationType.JMX_INVOKE),
            runtime -> runtime.beforeJmxInvoke(null, null, "gc")),
        hook(
            "native library load",
            OperationType.NATIVE_LIBRARY_LOAD,
            runtimeOp(OperationType.NATIVE_LIBRARY_LOAD),
            runtime -> runtime.beforeNativeLibraryLoad("nonexistent_chaos_lib_xyz")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hooks")
  @DisplayName("passes the call through, then evaluates it once a scenario selects it")
  void passthroughThenEvaluated(
      final String name,
      final OperationType operation,
      final ChaosSelector selector,
      final Hook hook)
      throws Throwable {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
      runtime.addEventListener(events::add);

      final long idle = timed(runtime, hook);

      assertThat(events).as("no event without a scenario").isEmpty();
      assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
      assertThat(idle).as("no delay without a scenario").isLessThan(DELAY.toNanos());

      runtime.activate(
          ChaosScenario.builder(name)
              .selector(selector)
              .effect(ChaosEffect.delay(DELAY))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long selected = timed(runtime, hook);

      assertThat(selected).as("delayed once selected").isGreaterThanOrEqualTo(DELAY.toNanos());
      assertThat(runtime.diagnostics().scenario(name).orElseThrow().appliedCount())
          .as(operation.name())
          .isEqualTo(1L);
    }
  }

  private static long timed(final ChaosRuntime runtime, final Hook hook) throws Throwable {
    final long start = System.nanoTime();
    hook.call(runtime);
    return System.nanoTime() - start;
  }
}
