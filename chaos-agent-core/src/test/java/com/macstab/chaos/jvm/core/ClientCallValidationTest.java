package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Client-library operations")
class ClientCallValidationTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("suppress is rejected: a client call can only be delayed, gated or rejected")
  void suppressRejected() {
    assertThatThrownBy(
            () ->
                runtime.activate(
                    scenario(ChaosSelector.redis(NamePattern.any()), ChaosEffect.suppress())))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining("REDIS_COMMAND");
  }

  @Test
  @DisplayName("a reject on Netty reads is rejected; writes may be rejected")
  void nettyReadReject() {
    assertThatThrownBy(
            () ->
                runtime.activate(
                    scenario(
                        new ChaosSelector.NettySelector(
                            Set.of(OperationType.NETTY_CHANNEL_READ), null, null),
                        ChaosEffect.reject("x"))))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining("NETTY_CHANNEL_READ");
    runtime.activate(
        scenario(
            new ChaosSelector.NettySelector(Set.of(OperationType.NETTY_CHANNEL_WRITE), null, null),
            ChaosEffect.reject("x")));
  }

  @Test
  @DisplayName("a consumer poll matches when any subscribed topic matches")
  void pollMatchesAnyTopic() throws Throwable {
    runtime.activate(
        scenario(
            ChaosSelector.messaging(
                Set.of(OperationType.MESSAGE_RECEIVE), NamePattern.exact("orders")),
            ChaosEffect.delay(Duration.ofMillis(1))));

    runtime.beforeClientCall("MESSAGE_RECEIVE", this, "audit,orders", null, null);
    runtime.beforeClientCall("MESSAGE_RECEIVE", this, "audit,payments", null, null);

    assertThat(runtime.diagnostics().scenario("client").orElseThrow().matchedCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("without the library on the class path a reject falls back to a generic failure")
  void rejectFallback() {
    runtime.activate(
        scenario(ChaosSelector.rpc(NamePattern.any()), ChaosEffect.reject("rpc down")));

    assertThatThrownBy(() -> runtime.beforeClientCall("RPC_CALL", this, "a.B/C", null, null))
        .hasMessage("rpc down");
  }

  @Nested
  @DisplayName("a gRPC reject, which fails the call through gRPC's FailingClientStream")
  class GrpcReject {

    @Test
    @DisplayName(
        "is refused when the gRPC on the class path has no FailingClientStream it can build")
    void refusedWithUnknownConstructor() throws Exception {
      final ClassLoader grpc = fakeGrpc(false);

      assertThatThrownBy(() -> activateWithContextLoader(grpc))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("RPC_CALL")
          .hasMessageContaining("io.grpc.internal.FailingClientStream");
    }

    @Test
    @DisplayName("is accepted when the FailingClientStream has a known constructor")
    void acceptedWithKnownConstructor() throws Exception {
      final ClassLoader grpc = fakeGrpc(true);

      activateWithContextLoader(grpc);

      assertThat(runtime.diagnostics().scenario("client")).isPresent();
    }

    @Test
    @DisplayName("is accepted when gRPC is not visible at activation")
    void acceptedWithoutGrpc() {
      runtime.activate(
          scenario(ChaosSelector.rpc(NamePattern.any()), ChaosEffect.reject("rpc down")));

      assertThat(runtime.diagnostics().scenario("client")).isPresent();
    }

    private void activateWithContextLoader(final ClassLoader loader) {
      final Thread thread = Thread.currentThread();
      final ClassLoader previous = thread.getContextClassLoader();
      thread.setContextClassLoader(loader);
      try {
        runtime.activate(
            scenario(ChaosSelector.rpc(NamePattern.any()), ChaosEffect.reject("rpc down")));
      } finally {
        thread.setContextClassLoader(previous);
      }
    }

    /**
     * A loader with {@code io.grpc.Status} and an {@code io.grpc.internal.FailingClientStream}
     * whose one constructor takes {@code (Status)} when {@code known}, else {@code (String)}.
     */
    private ClassLoader fakeGrpc(final boolean known) throws Exception {
      final net.bytebuddy.dynamic.loading.ByteArrayClassLoader loader =
          new net.bytebuddy.dynamic.loading.ByteArrayClassLoader(
              getClass().getClassLoader(), java.util.Map.of());
      final Class<?> status =
          new net.bytebuddy.ByteBuddy()
              .subclass(Object.class)
              .name("io.grpc.Status")
              .make()
              .load(loader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION)
              .getLoaded();
      new net.bytebuddy.ByteBuddy()
          .subclass(
              Object.class,
              net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default.NO_CONSTRUCTORS)
          .name("io.grpc.internal.FailingClientStream")
          .defineConstructor(net.bytebuddy.description.modifier.Visibility.PUBLIC)
          .withParameters(known ? status : String.class)
          .intercept(net.bytebuddy.implementation.MethodCall.invoke(Object.class.getConstructor()))
          .make()
          .load(loader, net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION);
      return loader;
    }
  }

  private static ChaosScenario scenario(final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosScenario.builder("client")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
