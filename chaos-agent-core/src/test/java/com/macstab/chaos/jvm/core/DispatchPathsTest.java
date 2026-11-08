package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dispatch paths for calls whose subject is missing or unusual, and the small helpers the dispatch
 * relies on.
 */
@DisplayName("Dispatch paths")
class DispatchPathsTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private void activate(final String id, final ChaosSelector selector, final ChaosEffect effect) {
    runtime.activate(
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(effect)
            .build());
  }

  private void rejectJvmRuntime(final OperationType operation) {
    activate(
        "reject-" + operation,
        ChaosSelector.jvmRuntime(Set.of(operation)),
        ChaosEffect.reject("refused"));
  }

  @Nested
  @DisplayName("JVM runtime operations without a subject")
  class JvmRuntimeOperations {

    @Test
    @DisplayName("calls with their subject are rejected too")
    void callsWithSubject() throws Exception {
      for (final OperationType operation :
          List.of(
              OperationType.REFLECTION_INVOKE,
              OperationType.OBJECT_DESERIALIZE,
              OperationType.OBJECT_SERIALIZE,
              OperationType.JNDI_LOOKUP,
              OperationType.JMX_INVOKE)) {
        rejectJvmRuntime(operation);
      }
      final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
        out.flush();
        assertThatThrownBy(() -> runtime.beforeObjectSerialize(out, "value"))
            .isInstanceOf(java.io.NotSerializableException.class);
      }
      try (java.io.ObjectInputStream in =
          new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
        assertThatThrownBy(() -> runtime.beforeObjectDeserialize(in))
            .isInstanceOf(java.io.InvalidClassException.class);
      }
      final javax.naming.InitialContext context = new javax.naming.InitialContext();
      assertThatThrownBy(() -> runtime.beforeJndiLookup(context, "java:comp/env"))
          .isInstanceOf(javax.naming.NamingException.class);
      final javax.management.MBeanServer server =
          java.lang.management.ManagementFactory.getPlatformMBeanServer();
      assertThatThrownBy(() -> runtime.beforeJmxInvoke(server, null, "reset"))
          .isInstanceOf(javax.management.MBeanException.class);
      final java.lang.reflect.Field field = String.class.getDeclaredField("value");
      assertThatThrownBy(() -> runtime.beforeReflectionInvoke(field, "text")).hasMessage("refused");
    }

    @Test
    @DisplayName("a reflective constructor call is rejected like a method call")
    void constructorInvocation() throws Exception {
      rejectJvmRuntime(OperationType.REFLECTION_INVOKE);
      final java.lang.reflect.Constructor<?> constructor = Object.class.getConstructor();
      assertThatThrownBy(() -> runtime.beforeReflectionInvoke(constructor, null))
          .hasMessage("refused");
    }

    @Test
    @DisplayName("a reflective call without a method object is still rejected")
    void missingMethod() {
      rejectJvmRuntime(OperationType.REFLECTION_INVOKE);
      assertThatThrownBy(() -> runtime.beforeReflectionInvoke(null, null)).hasMessage("refused");
    }

    @Test
    @DisplayName("a deserialization without a stream is rejected as an invalid class")
    void deserializeWithoutStream() {
      rejectJvmRuntime(OperationType.OBJECT_DESERIALIZE);
      assertThatThrownBy(() -> runtime.beforeObjectDeserialize(null))
          .isInstanceOf(java.io.InvalidClassException.class)
          .hasMessage("refused");
    }

    @Test
    @DisplayName("a serialization without a stream is rejected as not serializable")
    void serializeWithoutStream() {
      rejectJvmRuntime(OperationType.OBJECT_SERIALIZE);
      assertThatThrownBy(() -> runtime.beforeObjectSerialize(null, "value"))
          .isInstanceOf(java.io.NotSerializableException.class)
          .hasMessage("refused");
    }

    @Test
    @DisplayName("a JNDI lookup without a context is rejected as a naming failure")
    void jndiWithoutContext() {
      rejectJvmRuntime(OperationType.JNDI_LOOKUP);
      assertThatThrownBy(() -> runtime.beforeJndiLookup(null, "java:comp/env"))
          .isInstanceOf(javax.naming.NamingException.class)
          .hasMessage("refused");
    }

    @Test
    @DisplayName("a JMX invocation without a server is rejected as an MBean failure")
    void jmxWithoutServer() {
      rejectJvmRuntime(OperationType.JMX_INVOKE);
      assertThatThrownBy(() -> runtime.beforeJmxInvoke(null, null, "reset"))
          .isInstanceOf(javax.management.MBeanException.class)
          .hasMessage("refused");
    }
  }

  @Nested
  @DisplayName("threads without a subject")
  class Threads {

    @Test
    @DisplayName("a monitor entry without a lock is matched as an AQS acquire")
    void monitorWithoutLock() throws Throwable {
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("aqs")
                  .selector(
                      new ChaosSelector.MonitorSelector(
                          Set.of(OperationType.MONITOR_ENTER),
                          NamePattern.exact(
                              "java.util.concurrent.locks.AbstractQueuedSynchronizer")))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .build());
      runtime.beforeMonitorEnter(null);
      assertThat(handle.appliedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a thread-local access without the thread local is matched as ThreadLocal")
    void threadLocalWithoutInstance() throws Throwable {
      activate(
          "tl",
          new ChaosSelector.ThreadLocalSelector(
              Set.of(OperationType.THREAD_LOCAL_GET, OperationType.THREAD_LOCAL_SET),
              NamePattern.exact("java.lang.ThreadLocal")),
          ChaosEffect.suppress());
      assertThat(runtime.beforeThreadLocalGet(null)).isTrue();
      assertThat(runtime.beforeThreadLocalSet(null, "value")).isTrue();
    }

    @Test
    @DisplayName("a method entry with no method scenario proceeds")
    void methodEnterWithoutScenario() {
      assertThatCode(() -> runtime.beforeMethodEnter("com.example.A", "run"))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("async cancel")
  class AsyncCancel {

    @Test
    @DisplayName("an exceptional completion leaves a cancel of a non-CompletableFuture alone")
    void exceptionalCompletionNeedsCompletableFuture() throws Throwable {
      activate(
          "cancel",
          ChaosSelector.async(Set.of(OperationType.ASYNC_CANCEL)),
          ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.IO, "lost"));
      final CompletableFuture<String> future = new CompletableFuture<>();

      assertThat(runtime.beforeAsyncCancel(new Object(), false)).isFalse();
      assertThat(runtime.beforeAsyncCancel(future, false)).isTrue();
      assertThat(future).isCompletedExceptionally();
    }
  }

  @Nested
  @DisplayName("network")
  class Network {

    @Test
    @DisplayName("a redirect without a port of a non-IP address connects on port 0")
    void redirectWithoutPortOfUnixAddress() throws Throwable {
      activate(
          "redirect",
          ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT)),
          ChaosEffect.redirect("127.0.0.1"));
      final InetSocketAddress target =
          runtime.beforeSocketConnect(null, UnixDomainSocketAddress.of("/tmp/chaos.sock"), 100);
      assertThat(target).isNotNull();
      assertThat(target.getHostString()).isEqualTo("127.0.0.1");
      assertThat(target.getPort()).isZero();
    }

    @Test
    @DisplayName("a connection reset sets linger 0 on an open socket only")
    void connectionResetLingers() throws Throwable {
      activate(
          "reset",
          ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
          ChaosEffect.connectionReset());
      try (Socket open = new Socket()) {
        runtime.beforeSocketClose(open);
        assertThat(open.getSoLinger()).isZero();
      }
      final Socket closed = new Socket();
      closed.close();
      assertThatCode(() -> runtime.beforeSocketClose(closed)).doesNotThrowAnyException();
      assertThat(closed.isClosed()).isTrue();
    }

    @Test
    @DisplayName("a connection reset leaves channels without linger, closed channels and others")
    void connectionResetSkipsWhatCannotLinger() throws Throwable {
      activate(
          "reset-channels",
          ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)),
          ChaosEffect.connectionReset());
      try (DatagramChannel datagram = DatagramChannel.open()) {
        runtime.beforeSocketClose(datagram);
        assertThat(datagram.isOpen()).isTrue();
      }
      final SocketChannel closedChannel = SocketChannel.open();
      closedChannel.close();
      assertThatCode(() -> runtime.beforeSocketClose(closedChannel)).doesNotThrowAnyException();
      assertThatCode(() -> runtime.beforeSocketClose(new Object())).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("clients")
  class Clients {

    @Test
    @DisplayName("a rejected client call without a client object fails with the generic rejection")
    void clientCallWithoutClient() {
      activate("rpc", ChaosSelector.rpc(NamePattern.any()), ChaosEffect.reject("unavailable"));
      assertThatThrownBy(() -> runtime.beforeClientCall("RPC_CALL", null, "svc/Get", null, null))
          .hasMessage("unavailable");
    }

    @Test
    @DisplayName("a statement is matched on its first 200 characters")
    void longStatementIsTruncated() {
      final String head = "SELECT " + "x".repeat(193);
      assertThat(head).hasSize(200);
      activate(
          "jdbc",
          new ChaosSelector.JdbcSelector(
              Set.of(OperationType.JDBC_STATEMENT_EXECUTE), NamePattern.exact(head)),
          ChaosEffect.reject("db down"));
      assertThatThrownBy(() -> runtime.beforeJdbcStatementExecute(head + " FROM t"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessage("db down");
    }
  }

  @Nested
  @DisplayName("clock")
  class ClockReads {

    @Test
    @DisplayName("two session clock skews of the current session both apply")
    void twoSessionSkewsCompose() {
      final ChaosSession session = runtime.openSession("clock");
      try {
        for (final long minutes : new long[] {1, 2}) {
          session.activate(
              ChaosScenario.builder("skew-" + minutes)
                  .scope(ChaosScenario.ScenarioScope.SESSION)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofMinutes(minutes), ChaosEffect.ClockSkewMode.FIXED))
                  .build());
        }
        assertThat(runtime.adjustClockMillis(1_000L))
            .isEqualTo(1_000L + Duration.ofMinutes(3).toMillis());
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("a drift that overflows saturates and stays saturated")
    void driftSaturates() {
      final long half = Long.MAX_VALUE / 2 + 1;
      final ClockSkewState state =
          new ClockSkewState(
              new ChaosEffect.ClockSkewEffect(
                  Duration.ofNanos(half), ChaosEffect.ClockSkewMode.DRIFT),
              0L,
              0L,
              null);
      assertThat(state.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(half);
      assertThat(state.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MAX_VALUE);
      assertThat(state.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("helpers")
  class Helpers {

    @Test
    @DisplayName("each primitive return descriptor resolves to its primitive type")
    void primitiveReturnTypes() {
      assertThat(MethodDescriptors.returnType(DispatchPathsTest.class, "()B"))
          .isEqualTo(byte.class);
      assertThat(MethodDescriptors.returnType(DispatchPathsTest.class, "()C"))
          .isEqualTo(char.class);
      assertThat(MethodDescriptors.returnType(DispatchPathsTest.class, "()S"))
          .isEqualTo(short.class);
      assertThat(MethodDescriptors.returnType(DispatchPathsTest.class, "()F"))
          .isEqualTo(float.class);
      assertThat(MethodDescriptors.returnType(DispatchPathsTest.class, "()D"))
          .isEqualTo(double.class);
    }

    @Test
    @DisplayName("a shaped DNS answer passes through a missing answer and keeps a short one whole")
    void dnsShapes() throws Exception {
      final ChaosEffect.DnsAnswerEffect limit = ChaosEffect.dnsLimit(5);
      assertThat(DnsAnswers.shape(limit, "db", null, null, new Random(1))).isNull();
      final InetAddress[] answer = {
        InetAddress.getByName("10.0.0.1"), InetAddress.getByName("10.0.0.2")
      };
      assertThat(DnsAnswers.shape(limit, "db", answer, null, new Random(1)))
          .containsExactly(answer);
    }

    @Test
    @DisplayName("a missing thread is not virtual")
    void nullThreadIsNotVirtual() {
      assertThat(new FeatureSet().isVirtualThread(null)).isFalse();
    }

    @Test
    @DisplayName("a primitive, an unknown type and an unbounded type fall back to ZERO, logged")
    void corruptionFallbacksAreLogged() {
      try (LogCapture log = LogCapture.agent()) {
        assertThat(
                ReturnValueCorruptor.corrupt(
                    ChaosEffect.ReturnValueStrategy.NULL, int.class, 7, "s1"))
            .isEqualTo(0);
        assertThat(
                ReturnValueCorruptor.corrupt(
                    ChaosEffect.ReturnValueStrategy.EMPTY, Thread.class, null, "s2"))
            .isNull();
        assertThat(
                ReturnValueCorruptor.corrupt(
                    ChaosEffect.ReturnValueStrategy.BOUNDARY_MAX, String.class, "v", "s3"))
            .isNull();
        assertThat(log.messages())
            .contains(
                "ReturnValueCorruption NULL is inapplicable to primitive int in scenario s1;"
                    + " falling back to ZERO",
                "ReturnValueCorruption EMPTY is inapplicable to java.lang.Thread in scenario s2;"
                    + " falling back to ZERO",
                "ReturnValueCorruption BOUNDARY_MAX is inapplicable to java.lang.String in"
                    + " scenario s3; falling back to ZERO");
      }
    }
  }

  @Nested
  @DisplayName("ManualGate")
  class Gate {

    @Test
    @DisplayName("a released gate lets every non-positive, missing or unbounded wait through")
    void releasedGatePasses() {
      final ManualGate gate = new ManualGate();
      gate.release();
      assertThatCode(
              () -> {
                gate.await(null);
                gate.await(Duration.ZERO);
                gate.await(Duration.ofMillis(-1));
                gate.await(Duration.ofSeconds(Long.MAX_VALUE));
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("SessionHandoff")
  class Handoff {

    @Test
    @DisplayName("nothing is captured without a session, and a carrier captured twice runs once")
    void captureCountsOncePerCarrier() {
      final SessionHandoff handoff = new SessionHandoff();
      final ScopeContext scope = new ScopeContext();
      handoff.capture(null, "s");
      final Object withoutSession = new Object();
      handoff.capture(withoutSession, null);
      handoff.enter(withoutSession, scope);
      assertThat(scope.currentSessionId()).isNull();

      final Object carrier = new Object();
      handoff.capture(carrier, "s");
      handoff.capture(carrier, "s");
      handoff.enter(carrier, scope);
      assertThat(scope.currentSessionId()).isEqualTo("s");
      handoff.exit(carrier);
      handoff.enter(carrier, scope);
      assertThat(scope.currentSessionId()).isNull();
    }

    @Test
    @DisplayName("entering or leaving without a carrier does nothing")
    void nullCarrierIsIgnored() {
      final SessionHandoff handoff = new SessionHandoff();
      final ScopeContext scope = new ScopeContext();
      handoff.capture(new Object(), "s");
      handoff.enter(null, scope);
      handoff.exit(null);
      assertThat(scope.currentSessionId()).isNull();
    }

    @Test
    @DisplayName("a binding broken while the carrier ran is released quietly")
    void brokenBindingIsLogged() {
      final SessionHandoff handoff = new SessionHandoff();
      final ScopeContext scope = new ScopeContext();
      final Object carrier = new Object();
      handoff.capture(carrier, "s");
      handoff.enter(carrier, scope);
      assertThat(scope.currentSessionId()).isEqualTo("s");
      final ChaosSession.ScopeBinding extra = scope.bind("s");
      extra.close();
      extra.close();

      try (LogCapture log = LogCapture.agent()) {
        assertThatCode(() -> handoff.exit(carrier)).doesNotThrowAnyException();
        assertThat(log.records())
            .anyMatch(
                record ->
                    record.getLevel() == Level.FINE
                        && record.getMessage().equals("chaos session hand-off release failed")
                        && record.getThrown() instanceof IllegalStateException);
      }
      assertThat(scope.currentSessionId()).isNull();
    }
  }
}
