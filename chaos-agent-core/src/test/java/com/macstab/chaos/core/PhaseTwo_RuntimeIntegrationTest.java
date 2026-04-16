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
  // Socket accept
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeSocketAccept")
  class SocketAcceptTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-accept-delay")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_ACCEPT), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeSocketAccept(null);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("RejectEffect throws on beforeSocketAccept")
    void rejectEffectThrows() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-accept-reject")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_ACCEPT), NamePattern.any()))
                  .effect(ChaosEffect.reject("chaos accept rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThatThrownBy(() -> runtime.beforeSocketAccept(null)).isInstanceOf(Throwable.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeSocketAccept(null);
    }
  }

  // ---------------------------------------------------------------------------
  // Socket read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeSocketRead")
  class SocketReadTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-read-delay")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_READ), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeSocketRead(null);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("RejectEffect throws SocketTimeoutException")
    void rejectEffectThrowsSocketTimeoutException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-read-reject")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_READ), NamePattern.any()))
                  .effect(ChaosEffect.reject("chaos read rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeSocketRead(null));
      assertThat(thrown).isInstanceOf(java.net.SocketTimeoutException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeSocketRead(null);
    }
  }

  // ---------------------------------------------------------------------------
  // Socket write
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeSocketWrite")
  class SocketWriteTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-write-delay")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_WRITE), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeSocketWrite(null, 64);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("RejectEffect throws IOException")
    void rejectEffectThrowsIoException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-write-reject")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_WRITE), NamePattern.any()))
                  .effect(ChaosEffect.reject("chaos write rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown = catchThrowable(() -> runtime.beforeSocketWrite(null, 64));
      assertThat(thrown).isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeSocketWrite(null, 64);
    }
  }

  // ---------------------------------------------------------------------------
  // Socket close
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeSocketClose")
  class SocketCloseTests {

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("socket-close-delay")
                  .selector(
                      new ChaosSelector.NetworkSelector(
                          Set.of(OperationType.SOCKET_CLOSE), NamePattern.any()))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeSocketClose(null);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeSocketClose(null);
    }
  }

  // ---------------------------------------------------------------------------
  // JNDI lookup
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeJndiLookup")
  class JndiLookupTests {

    @Test
    @DisplayName("RejectEffect throws NamingException (or RuntimeException if javax.naming absent)")
    void rejectEffectThrowsNamingException() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("jndi-reject")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.JNDI_LOOKUP)))
                  .effect(ChaosEffect.reject("chaos jndi rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      assertThatThrownBy(() -> runtime.beforeJndiLookup(null, "java:comp/env/test"))
          .isInstanceOf(Throwable.class);
    }

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("jndi-delay")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.JNDI_LOOKUP)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeJndiLookup(null, "java:comp/env/test");
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeJndiLookup(null, "java:comp/env/test");
    }
  }

  // ---------------------------------------------------------------------------
  // JMX MBeanServer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeJmxGetAttr / beforeJmxInvoke")
  class JmxTests {

    @Test
    @DisplayName("delay effect adds latency to getAttribute path")
    void delayEffectAddsLatencyToGetAttr() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("jmx-get-attr-delay")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_GET_ATTR)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeJmxGetAttr(null, null, "VmName");
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("delay effect adds latency to invoke path")
    void delayEffectAddsLatencyToInvoke() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("jmx-invoke-delay")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_INVOKE)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeJmxInvoke(null, null, "gc");
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — getAttribute does not throw")
    void noScenarioDoesNotThrowGetAttr() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeJmxGetAttr(null, null, "VmName");
    }

    @Test
    @DisplayName("no scenario — invoke does not throw")
    void noScenarioDoesNotThrowInvoke() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeJmxInvoke(null, null, "gc");
    }
  }

  // ---------------------------------------------------------------------------
  // Native library load
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("beforeNativeLibraryLoad")
  class NativeLibraryLoadTests {

    @Test
    @DisplayName("RejectEffect throws UnsatisfiedLinkError")
    void rejectEffectThrowsUnsatisfiedLinkError() {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("native-reject")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.NATIVE_LIBRARY_LOAD)))
                  .effect(ChaosEffect.reject("chaos native load rejection"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final Throwable thrown =
          catchThrowable(() -> runtime.beforeNativeLibraryLoad("nonexistent_chaos_lib_xyz"));
      assertThat(thrown).isInstanceOf(UnsatisfiedLinkError.class);
    }

    @Test
    @DisplayName("delay effect adds latency")
    void delayEffectAddsLatency() throws Throwable {
      final ChaosRuntime runtime =
          runtimeWith(
              ChaosScenario.builder("native-delay")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.NATIVE_LIBRARY_LOAD)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(50)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      final long start = System.nanoTime();
      runtime.beforeNativeLibraryLoad("nonexistent_chaos_lib_xyz");
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("no scenario — does not throw")
    void noScenarioDoesNotThrow() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.beforeNativeLibraryLoad("nonexistent_chaos_lib_xyz");
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
