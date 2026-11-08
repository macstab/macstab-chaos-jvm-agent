package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.SyncFailedException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exception a rejected or failed operation surfaces as: the type the application's own error
 * handling catches for that kind of call, built reflectively where the type lives in an optional
 * JDK module.
 */
@DisplayName("Failure factory")
class FailureFactoryTest {

  @Nested
  @DisplayName("rejections from optional JDK modules")
  class OptionalModules {

    @Test
    @DisplayName("a JNDI lookup fails with NamingException")
    void jndi() {
      assertThat(FailureFactory.reject(call(OperationType.JNDI_LOOKUP), "no name"))
          .isInstanceOf(javax.naming.NamingException.class)
          .hasMessage("no name");
    }

    @Test
    @DisplayName("a JMX call fails with MBeanException carrying the message")
    void jmx() {
      assertThat(FailureFactory.reject(call(OperationType.JMX_INVOKE), "no bean"))
          .isInstanceOf(javax.management.MBeanException.class)
          .hasMessageContaining("no bean");
    }

    @Test
    @DisplayName("a TLS handshake fails with SSLHandshakeException")
    void ssl() {
      assertThat(FailureFactory.reject(call(OperationType.SSL_HANDSHAKE), "bad cert"))
          .isInstanceOf(javax.net.ssl.SSLHandshakeException.class)
          .hasMessage("bad cert");
    }

    @Test
    @DisplayName("a JDBC call fails with SQLException")
    void jdbc() {
      assertThat(FailureFactory.reject(call(OperationType.JDBC_STATEMENT_EXECUTE), "db down"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessage("db down");
    }
  }

  @Nested
  @DisplayName("file errno failures")
  class FileErrno {

    @Test
    @DisplayName("a failed sync is a SyncFailedException")
    void sync() {
      assertThat(
              FailureFactory.errno(ChaosEffect.Errno.EIO, OperationType.FILE_IO_SYNC, "/x", false))
          .isExactlyInstanceOf(SyncFailedException.class);
    }

    @Test
    @DisplayName("a java.io close reports the error text without the path")
    void javaIoClose() {
      assertThat(
              FailureFactory.errno(ChaosEffect.Errno.EIO, OperationType.FILE_IO_CLOSE, "/x", true))
          .isExactlyInstanceOf(IOException.class)
          .hasMessage(ChaosEffect.Errno.EIO.reason());
    }

    @Test
    @DisplayName("a channel close reports the error text, as a descriptor call does")
    void nioClose() {
      assertThat(
              FailureFactory.errno(ChaosEffect.Errno.EIO, OperationType.FILE_IO_CLOSE, "/x", false))
          .isExactlyInstanceOf(IOException.class)
          .hasMessage(ChaosEffect.Errno.EIO.reason());
    }

    @Test
    @DisplayName("a path call names the path")
    void pathCall() {
      assertThat(
              FailureFactory.errno(
                  ChaosEffect.Errno.ENOENT, OperationType.FILE_IO_DELETE, "/x", false))
          .isExactlyInstanceOf(NoSuchFileException.class)
          .hasMessage("/x");
    }

    @Test
    @DisplayName(
        "without a path a missing file is FileNotFoundException, anything else IOException")
    void withoutAPath() {
      assertThat(
              FailureFactory.errno(
                  ChaosEffect.Errno.ENOENT, OperationType.FILE_IO_DELETE, null, false))
          .isExactlyInstanceOf(FileNotFoundException.class);
      assertThat(
              FailureFactory.errno(
                  ChaosEffect.Errno.EIO, OperationType.FILE_IO_DELETE, null, false))
          .isExactlyInstanceOf(IOException.class)
          .hasMessage(ChaosEffect.Errno.EIO.reason());
    }
  }

  /**
   * A reject raises what the real call raises (ADR 0005), through the entry point the advice calls.
   * Every operation is classified: it throws a given type, returns a value, or its reject is
   * refused at activation.
   */
  @Nested
  @DisplayName("a reject raises the failure of the real call")
  class RejectIsTheRealFailure {

    /** The exact failure a reject raises on each operation whose reject throws. */
    private static final Map<OperationType, Class<? extends Throwable>> THROWS =
        new EnumMap<>(OperationType.class);

    static {
      THROWS.put(OperationType.THREAD_START, OutOfMemoryError.class);
      THROWS.put(OperationType.VIRTUAL_THREAD_START, RejectedExecutionException.class);
      THROWS.put(OperationType.THREAD_SLEEP, InterruptedException.class);
      THROWS.put(OperationType.EXECUTOR_SUBMIT, RejectedExecutionException.class);
      THROWS.put(OperationType.SCHEDULE_SUBMIT, RejectedExecutionException.class);
      THROWS.put(OperationType.SCHEDULE_TICK, IllegalStateException.class);
      THROWS.put(OperationType.EXECUTOR_AWAIT_TERMINATION, InterruptedException.class);
      THROWS.put(OperationType.QUEUE_PUT, InterruptedException.class);
      THROWS.put(OperationType.SHUTDOWN_HOOK_REGISTER, IllegalStateException.class);
      THROWS.put(OperationType.CLASS_LOAD, ClassNotFoundException.class);
      THROWS.put(OperationType.CLASS_DEFINE, ClassFormatError.class);
      THROWS.put(OperationType.METHOD_ENTER, IllegalStateException.class);
      THROWS.put(OperationType.METHOD_EXIT, IllegalStateException.class);
      THROWS.put(OperationType.REFLECTION_INVOKE, InvocationTargetException.class);
      THROWS.put(OperationType.DIRECT_BUFFER_ALLOCATE, OutOfMemoryError.class);
      THROWS.put(OperationType.OBJECT_DESERIALIZE, java.io.InvalidClassException.class);
      THROWS.put(OperationType.OBJECT_SERIALIZE, java.io.NotSerializableException.class);
      THROWS.put(OperationType.NATIVE_LIBRARY_LOAD, UnsatisfiedLinkError.class);
      THROWS.put(OperationType.JNDI_LOOKUP, javax.naming.NamingException.class);
      THROWS.put(OperationType.JMX_INVOKE, javax.management.MBeanException.class);
      THROWS.put(OperationType.JMX_GET_ATTR, javax.management.MBeanException.class);
      THROWS.put(OperationType.ZIP_INFLATE, java.util.zip.DataFormatException.class);
      THROWS.put(OperationType.NIO_SELECTOR_SELECT, IOException.class);
      THROWS.put(OperationType.NIO_CHANNEL_READ, IOException.class);
      THROWS.put(OperationType.NIO_CHANNEL_WRITE, IOException.class);
      THROWS.put(OperationType.NIO_CHANNEL_CONNECT, IOException.class);
      THROWS.put(OperationType.NIO_CHANNEL_ACCEPT, IOException.class);
      THROWS.put(OperationType.SOCKET_CONNECT, java.net.ConnectException.class);
      THROWS.put(OperationType.SOCKET_ACCEPT, IOException.class);
      // the invoker's read has an SO_TIMEOUT, which then expires
      THROWS.put(OperationType.SOCKET_READ, java.net.SocketTimeoutException.class);
      THROWS.put(OperationType.SOCKET_WRITE, java.net.SocketException.class);
      THROWS.put(OperationType.SOCKET_CLOSE, IOException.class);
      THROWS.put(OperationType.SOCKET_BIND, java.net.BindException.class);
      THROWS.put(OperationType.SOCKET_SHUTDOWN, java.net.SocketException.class);
      THROWS.put(OperationType.DATAGRAM_SEND, IOException.class);
      THROWS.put(OperationType.DATAGRAM_RECEIVE, IOException.class);
      THROWS.put(OperationType.SSL_HANDSHAKE, javax.net.ssl.SSLHandshakeException.class);
      THROWS.put(OperationType.DNS_RESOLVE, java.net.UnknownHostException.class);
      THROWS.put(OperationType.DNS_ANSWER, java.net.UnknownHostException.class);
      THROWS.put(OperationType.DNS_REVERSE, java.net.UnknownHostException.class);
      THROWS.put(OperationType.FILE_IO_READ, IOException.class);
      THROWS.put(OperationType.FILE_IO_WRITE, IOException.class);
      THROWS.put(OperationType.FILE_IO_SYNC, SyncFailedException.class);
      THROWS.put(OperationType.FILE_IO_TRUNCATE, IOException.class);
      THROWS.put(OperationType.FILE_IO_DELETE, IOException.class);
      THROWS.put(OperationType.FILE_IO_MOVE, IOException.class);
      // the invoker's subject is a FileChannel: a java.nio open
      THROWS.put(OperationType.FILE_IO_OPEN, FileSystemException.class);
      THROWS.put(OperationType.FILE_IO_MAP, IOException.class);
      THROWS.put(OperationType.FILE_IO_CLOSE, IOException.class);
      THROWS.put(OperationType.FILE_IO_MOVE_TARGET, IOException.class);
      THROWS.put(OperationType.HTTP_CLIENT_SEND, IOException.class);
      THROWS.put(OperationType.HTTP_CLIENT_SEND_ASYNC, IOException.class);
      for (final OperationType jdbc :
          EnumSet.range(
              OperationType.JDBC_CONNECTION_ACQUIRE, OperationType.JDBC_TRANSACTION_ROLLBACK)) {
        THROWS.put(jdbc, java.sql.SQLException.class);
      }
      // no client library on the class path: the fallback
      for (final OperationType client :
          EnumSet.range(OperationType.MESSAGE_SEND, OperationType.AWS_SDK_CALL)) {
        THROWS.put(client, IOException.class);
      }
      THROWS.put(OperationType.NETTY_CHANNEL_WRITE, IOException.class);
      THROWS.put(OperationType.PROCESS_START, IOException.class);
      THROWS.put(OperationType.PROCESS_WAIT, InterruptedException.class);
    }

    /** Operations whose reject returns a value instead of throwing. */
    private static final Set<OperationType> RETURNS =
        EnumSet.of(
            OperationType.QUEUE_OFFER,
            OperationType.ASYNC_COMPLETE,
            OperationType.ASYNC_COMPLETE_EXCEPTIONALLY,
            OperationType.RESOURCE_LOAD);

    /** Operations no real call of which fails: a reject there is refused at activation. */
    private static final Set<OperationType> REFUSED =
        EnumSet.of(
            OperationType.EXECUTOR_WORKER_RUN,
            OperationType.FORK_JOIN_TASK_RUN,
            OperationType.EXECUTOR_SHUTDOWN,
            OperationType.QUEUE_TAKE,
            OperationType.QUEUE_POLL,
            OperationType.ASYNC_CANCEL,
            OperationType.MONITOR_ENTER,
            OperationType.THREAD_PARK,
            OperationType.THREAD_LOCAL_GET,
            OperationType.THREAD_LOCAL_SET,
            OperationType.SYSTEM_GC_REQUEST,
            OperationType.SYSTEM_EXIT_REQUEST,
            OperationType.ZIP_DEFLATE,
            OperationType.NETTY_CHANNEL_READ,
            OperationType.SYSTEM_CLOCK_MILLIS,
            OperationType.SYSTEM_CLOCK_NANOS,
            OperationType.INSTANT_NOW,
            OperationType.CLOCK_READ,
            OperationType.LOCAL_DATE_TIME_NOW,
            OperationType.ZONED_DATE_TIME_NOW,
            OperationType.DATE_NEW,
            OperationType.LIFECYCLE);

    static Stream<OperationType> throwingOperations() {
      return THROWS.keySet().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("throwingOperations")
    @DisplayName("the reject raises the exact type the call raises")
    void rejectRaisesTheRealType(final OperationType operation) {
      try (ChaosRuntime runtime = rejecting(operation)) {
        assertThatThrownBy(() -> EffectReachesTheCallTest.INVOKERS.get(operation).invoke(runtime))
            .isExactlyInstanceOf(THROWS.get(operation));
      }
    }

    @Test
    @DisplayName("every operation throws, returns or is refused, and only one of them")
    void everyOperationIsClassified() {
      final Set<OperationType> classified = EnumSet.copyOf(THROWS.keySet());
      classified.addAll(RETURNS);
      classified.addAll(REFUSED);
      assertThat(classified).isEqualTo(EnumSet.allOf(OperationType.class));
      assertThat(THROWS.size() + RETURNS.size() + REFUSED.size())
          .isEqualTo(OperationType.values().length);
    }

    static Stream<OperationType> refusedOperations() {
      return REFUSED.stream().filter(operation -> operation != OperationType.LIFECYCLE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedOperations")
    @DisplayName("a reject the factory has no real type for is refused at activation")
    void refusedOperationIsRefusedAtActivation(final OperationType operation) {
      for (final ChaosSelector selector :
          EffectReachesTheCallTest.selectorsByOperation().get(operation)) {
        try (ChaosRuntime runtime = new ChaosRuntime()) {
          assertThatThrownBy(
                  () ->
                      runtime.activate(
                          ChaosScenario.builder("reject")
                              .scope(ChaosScenario.ScenarioScope.JVM)
                              .selector(selector)
                              .effect(ChaosEffect.reject("chaos"))
                              .activationPolicy(ActivationPolicy.always())
                              .build()))
              .as("%s through %s", operation, selector.getClass().getSimpleName())
              .isExactlyInstanceOf(ChaosValidationException.class);
        }
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedOperations")
    @DisplayName("the factory's arm for a refused reject keeps the message, as its contract says")
    void refusedArmKeepsTheMessage(final OperationType operation) {
      final InvocationContext context =
          new InvocationContext(operation, null, null, null, false, null, null, null);

      assertThat(FailureFactory.reject(context, "chaos"))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("chaos");
    }

    @Test
    @DisplayName("a socket read without SO_TIMEOUT fails as a broken connection, not a timeout")
    void socketReadWithoutTimeout() {
      try (ChaosRuntime runtime = rejecting(OperationType.SOCKET_READ)) {
        assertThatThrownBy(
                () -> runtime.beforeSocketRead(null, 64, InetAddress.getLoopbackAddress(), 9, 0))
            .isExactlyInstanceOf(java.net.SocketException.class)
            .hasMessage("chaos");
      }
    }

    @Test
    @DisplayName("a java.io open fails with FileNotFoundException")
    void javaIoOpen() throws IOException {
      try (ChaosRuntime runtime = rejecting(OperationType.FILE_IO_OPEN);
          FileInputStream stream = new FileInputStream(FileDescriptor.in)) {
        assertThatThrownBy(() -> runtime.beforeFileIo("FILE_IO_OPEN", stream, "/tmp/probe", 0))
            .isExactlyInstanceOf(FileNotFoundException.class);
      }
    }

    @Test
    @DisplayName("a java.nio open names the path")
    void nioOpenNamesThePath() {
      try (ChaosRuntime runtime = rejecting(OperationType.FILE_IO_OPEN)) {
        assertThatThrownBy(
                () ->
                    runtime.beforeFileIo(
                        "FILE_IO_OPEN",
                        new EffectReachesTheCallTest.ProbeFileChannelImpl(),
                        "/tmp/probe",
                        0))
            .isExactlyInstanceOf(FileSystemException.class)
            .satisfies(
                failure -> {
                  assertThat(((FileSystemException) failure).getFile()).isEqualTo("/tmp/probe");
                  assertThat(((FileSystemException) failure).getReason()).isEqualTo("chaos");
                });
      }
    }

    @Test
    @DisplayName("a reflective call fails with the target's exception as the cause")
    void reflectionHasACause() {
      try (ChaosRuntime runtime = rejecting(OperationType.REFLECTION_INVOKE)) {
        assertThatThrownBy(
                () ->
                    EffectReachesTheCallTest.INVOKERS
                        .get(OperationType.REFLECTION_INVOKE)
                        .invoke(runtime))
            .isExactlyInstanceOf(InvocationTargetException.class)
            .hasMessage("chaos")
            .cause()
            .isExactlyInstanceOf(RuntimeException.class)
            .hasMessage("chaos");
      }
    }

    @Test
    @DisplayName("thread starts and shutdown hooks fail with the JVM's own text")
    void jvmTexts() {
      try (ChaosRuntime runtime = rejecting(OperationType.THREAD_START)) {
        assertThatThrownBy(
                () ->
                    EffectReachesTheCallTest.INVOKERS
                        .get(OperationType.THREAD_START)
                        .invoke(runtime))
            .hasMessage(
                "unable to create native thread: possibly out of memory or process/resource"
                    + " limits reached");
      }
      try (ChaosRuntime runtime = rejecting(OperationType.SHUTDOWN_HOOK_REGISTER)) {
        assertThatThrownBy(() -> runtime.decorateShutdownHook(new Thread(() -> {})))
            .hasMessage("Shutdown in progress");
      }
    }

    /** A runtime with a reject on {@code operation}, through the first selector that takes it. */
    private ChaosRuntime rejecting(final OperationType operation) {
      final ChaosRuntime runtime = new ChaosRuntime();
      for (final ChaosSelector selector :
          EffectReachesTheCallTest.selectorsByOperation().get(operation)) {
        try {
          runtime.activate(
              ChaosScenario.builder("reject")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(selector)
                  .effect(ChaosEffect.reject("chaos"))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
          return runtime;
        } catch (final RuntimeException refused) {
          // the next selector of the operation
        }
      }
      runtime.close();
      throw new AssertionError("no selector takes a reject on " + operation);
    }
  }

  /** A call of {@code operation} without further detail. */
  private static InvocationContext call(final OperationType operation) {
    return new InvocationContext(operation, null, null, null, false, null, null, null);
  }
}
