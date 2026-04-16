package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Phase 2 {@link ChaosRuntime} interception points. Tests call {@code
 * before*()} methods directly — no ByteBuddy install required.
 */
@DisplayName("Phase 2 ChaosRuntime interception")
class PhaseTwo_RuntimeIntegrationTest {

  // ---------------------------------------------------------------------------
  // GC request
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeGcRequest")
  class GcRequestTests {

    @Test
    @DisplayName("SuppressEffect returns true (GC suppressed)")
    void suppressEffectReturnsTrueForGc() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("gc-suppress")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThat(runtime.beforeGcRequest()).isTrue();
    }

    @Test
    @DisplayName("no scenario — returns false (GC not suppressed)")
    void noScenarioReturnsFalse() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThat(runtime.beforeGcRequest()).isFalse();
    }

    @Test
    @DisplayName("delay effect — returns false after sleep")
    void delayEffectReturnsFalseAfterSleep() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("gc-delay")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      final boolean result = runtime.beforeGcRequest();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(result).isFalse();
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Exit request
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeExitRequest")
  class ExitRequestTests {

    @Test
    @DisplayName("SuppressEffect throws SecurityException")
    void suppressEffectThrowsSecurityException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("exit-suppress")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThatThrownBy(() -> runtime.beforeExitRequest(0)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeExitRequest(0);
    }
  }

  // ---------------------------------------------------------------------------
  // NIO selector
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeNioSelect")
  class NioSelectTests {

    @Test
    @DisplayName("SuppressEffect returns true (spurious wakeup injected)")
    void suppressEffectReturnsTrueForNioSelect() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("nio-suppress")
                  .selector(
                      new ChaosSelector.NioSelector(
                          Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThat(runtime.beforeNioSelect(null, 1000L)).isTrue();
    }

    @Test
    @DisplayName("no scenario — returns false (no spurious wakeup)")
    void noScenarioReturnsFalse() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThat(runtime.beforeNioSelect(null, 1000L)).isFalse();
    }

    @Test
    @DisplayName("delay effect — returns false after delay")
    void delayEffectReturnsFalseAfterDelay() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("nio-delay")
                  .selector(
                      new ChaosSelector.NioSelector(
                          Set.of(OperationType.NIO_SELECTOR_SELECT), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      final boolean result = runtime.beforeNioSelect(null, 1000L);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(result).isFalse();
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Async cancel
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeAsyncCancel")
  class AsyncCancelTests {

    @Test
    @DisplayName("SuppressEffect returns true (cancel suppressed)")
    void suppressEffectReturnsTrueForAsyncCancel() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("async-suppress")
                  .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)))
                  .effect(ChaosEffect.suppress())
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThat(runtime.beforeAsyncCancel(null, true)).isTrue();
    }

    @Test
    @DisplayName("no scenario — returns false (cancel proceeds)")
    void noScenarioReturnsFalse() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThat(runtime.beforeAsyncCancel(null, true)).isFalse();
    }

    @Test
    @DisplayName("RejectEffect throws")
    void rejectEffectThrows() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("async-fail")
                  .selector(ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)))
                  .effect(ChaosEffect.reject("cancel rejected by chaos"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThatThrownBy(() -> runtime.beforeAsyncCancel(null, true)).isInstanceOf(Throwable.class);
    }
  }

  // ---------------------------------------------------------------------------
  // Object deserialization
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeObjectDeserialize")
  class ObjectDeserializeTests {

    @Test
    @DisplayName("RejectEffect throws InvalidClassException")
    void rejectEffectThrowsInvalidClassException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("deser-reject")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.OBJECT_DESERIALIZE),
                          NamePattern.exact("java.io.ObjectInputStream"),
                          NamePattern.any()))
                  .effect(ChaosEffect.reject("chaos rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeObjectDeserialize(null));
      assertThat(thrown).isInstanceOf(java.io.InvalidClassException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeObjectDeserialize(null);
    }

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("deser-delay")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.OBJECT_DESERIALIZE),
                          NamePattern.exact("java.io.ObjectInputStream"),
                          NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeObjectDeserialize(null);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Object serialization
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeObjectSerialize")
  class ObjectSerializeTests {

    @Test
    @DisplayName("RejectEffect throws NotSerializableException")
    void rejectEffectThrowsNotSerializableException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("ser-reject")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.OBJECT_SERIALIZE),
                          NamePattern.exact("java.io.ObjectOutputStream"),
                          NamePattern.any()))
                  .effect(ChaosEffect.reject("chaos rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeObjectSerialize(null, null));
      assertThat(thrown).isInstanceOf(java.io.NotSerializableException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeObjectSerialize(null, null);
    }
  }

  // ---------------------------------------------------------------------------
  // ZIP inflate / deflate
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeZipInflate")
  class ZipInflateTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("zip-inflate-delay")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.ZIP_INFLATE),
                          NamePattern.exact("java.util.zip.Inflater"),
                          NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeZipInflate();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeZipInflate();
    }
  }

  @Nested
  @DisplayName("beforeZipDeflate")
  class ZipDeflateTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("zip-deflate-delay")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.ZIP_DEFLATE),
                          NamePattern.exact("java.util.zip.Deflater"),
                          NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeZipDeflate();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeZipDeflate();
    }
  }

  // ---------------------------------------------------------------------------
  // Direct buffer allocation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeDirectBufferAllocate")
  class DirectBufferAllocateTests {

    @Test
    @DisplayName("RejectEffect throws OutOfMemoryError")
    void rejectEffectThrowsOutOfMemoryError() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("direct-buffer-reject")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.DIRECT_BUFFER_ALLOCATE),
                          NamePattern.exact("java.nio.ByteBuffer"),
                          NamePattern.any()))
                  .effect(ChaosEffect.reject("direct buffer exhausted"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeDirectBufferAllocate(1024));
      assertThat(thrown).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeDirectBufferAllocate(1024);
    }
  }

  // ---------------------------------------------------------------------------
  // Reflection invoke
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeReflectionInvoke")
  class ReflectionInvokeTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("reflect-delay")
                  .selector(
                      ChaosSelector.method(
                          Set.of(OperationType.REFLECTION_INVOKE),
                          NamePattern.exact("java.lang.reflect.Method"),
                          NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeReflectionInvoke(null, null);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeReflectionInvoke(null, null);
    }
  }

  // ---------------------------------------------------------------------------
  // Socket connect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeSocketConnect")
  class SocketConnectTests {

    @Test
    @DisplayName("RejectEffect throws ConnectException")
    void rejectEffectThrowsConnectException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-connect-reject")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
                  .effect(ChaosEffect.reject("connection refused by chaos"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeSocketConnect(null, null, 0));
      assertThat(thrown).isInstanceOf(java.net.ConnectException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeSocketConnect(null, null, 0);
    }

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-connect-delay")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeSocketConnect(null, null, 0);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Class define
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeClassDefine")
  class ClassDefineTests {

    @Test
    @DisplayName("RejectEffect throws ClassFormatError")
    void rejectEffectThrowsClassFormatError() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("class-define-reject")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(
                      ChaosSelector.classLoading(
                          Set.of(OperationType.CLASS_DEFINE), NamePattern.any()))
                  .effect(ChaosEffect.reject("bad class format"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown =
          catchThrowable(() -> runtime.beforeClassDefine(null, "com.example.Foo"));
      assertThat(thrown).isInstanceOf(ClassFormatError.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeClassDefine(null, "com.example.Foo");
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static ChaosRuntime runtimeWith(final ChaosScenario scenario) {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(scenario);
    return runtime;
  }
}
