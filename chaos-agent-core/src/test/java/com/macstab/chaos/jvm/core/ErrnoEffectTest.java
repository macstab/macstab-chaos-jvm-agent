package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEffect.Errno;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetAddress;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ErrnoEffect")
class ErrnoEffectTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("network errors become the java.net exceptions with the system's text")
  void networkErrors() throws Exception {
    activate(ChaosSelector.network(Set.of(OperationType.SOCKET_WRITE)), Errno.ECONNRESET);

    assertThatThrownBy(
            () -> runtime.beforeSocketWrite(this, 10, InetAddress.getLoopbackAddress(), 80))
        .isExactlyInstanceOf(java.net.SocketException.class)
        .hasMessage("Connection reset");
  }

  @Test
  @DisplayName(
      "ETIMEDOUT is a ConnectException on connect and a dead connection, not SO_TIMEOUT, on a read")
  void timeoutOnConnectAndRead() {
    assertThat(FailureFactory.errno(Errno.ETIMEDOUT, OperationType.SOCKET_CONNECT, null, false))
        .isExactlyInstanceOf(java.net.ConnectException.class);
    for (final OperationType read :
        Set.of(OperationType.SOCKET_READ, OperationType.NIO_CHANNEL_READ)) {
      assertThat(FailureFactory.errno(Errno.ETIMEDOUT, read, null, false))
          .isExactlyInstanceOf(java.net.SocketException.class)
          .hasMessage("Connection timed out");
    }
  }

  @Test
  @DisplayName("refused and unreachable on an established connection are socket errors")
  void connectErrorsOnTransfer() {
    for (final OperationType transfer :
        Set.of(OperationType.SOCKET_READ, OperationType.SOCKET_WRITE)) {
      assertThat(FailureFactory.errno(Errno.ECONNREFUSED, transfer, null, false))
          .isExactlyInstanceOf(java.net.SocketException.class);
      assertThat(FailureFactory.errno(Errno.EHOSTUNREACH, transfer, null, false))
          .isExactlyInstanceOf(java.net.SocketException.class);
    }
    assertThat(FailureFactory.errno(Errno.ECONNREFUSED, OperationType.SOCKET_CONNECT, null, false))
        .isExactlyInstanceOf(java.net.ConnectException.class);
    assertThat(FailureFactory.errno(Errno.EHOSTUNREACH, OperationType.SOCKET_CONNECT, null, false))
        .isExactlyInstanceOf(java.net.NoRouteToHostException.class);
  }

  @Test
  @DisplayName("file errors of a path call carry the file's path")
  void fileErrorsCarryPath() {
    activate(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_OPEN)), Errno.ENOSPC);

    assertThatThrownBy(
            () -> runtime.beforeFileIo("FILE_IO_OPEN", this, "/var/lib/app/wal.log", 512))
        .isExactlyInstanceOf(FileSystemException.class)
        .satisfies(
            failure -> {
              assertThat(((FileSystemException) failure).getFile())
                  .isEqualTo("/var/lib/app/wal.log");
              assertThat(((FileSystemException) failure).getReason())
                  .isEqualTo("No space left on device");
            });
  }

  @Test
  @DisplayName("EACCES and ENOENT of a path call use the specific java.nio.file exceptions")
  void specificFileExceptions() {
    assertThat(FailureFactory.errno(Errno.EACCES, OperationType.FILE_IO_OPEN, "/x", false))
        .isExactlyInstanceOf(AccessDeniedException.class);
    assertThat(FailureFactory.errno(Errno.ENOENT, OperationType.FILE_IO_DELETE, "/x", false))
        .isExactlyInstanceOf(NoSuchFileException.class);
  }

  @Test
  @DisplayName("an error of the wrong family is rejected at activation")
  void familyMismatch() {
    assertThatThrownBy(
            () -> activate(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)), Errno.EPIPE))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageContaining("network error");
  }

  private void activate(final ChaosSelector selector, final Errno errno) {
    runtime.activate(
        ChaosScenario.builder("errno")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(selector)
            .effect(ChaosEffect.errno(errno))
            .activationPolicy(ActivationPolicy.always())
            .build());
  }

  @Test
  @DisplayName("opens, binds, accepts and thread starts fail as the JDK does")
  void newOperations() {
    assertThat(FailureFactory.errno(Errno.EMFILE, OperationType.FILE_IO_OPEN, "/x", true))
        .isInstanceOf(java.io.FileNotFoundException.class)
        .hasMessage("/x (Too many open files)");
    assertThat(FailureFactory.errno(Errno.ENOENT, OperationType.FILE_IO_OPEN, "/x", false))
        .isInstanceOf(java.nio.file.NoSuchFileException.class);
    assertThat(FailureFactory.errno(Errno.ENFILE, OperationType.FILE_IO_OPEN, "/x", false))
        .isInstanceOf(java.nio.file.FileSystemException.class)
        .hasMessage("/x: Too many open files in system");
    assertThat(FailureFactory.errno(Errno.EADDRNOTAVAIL, OperationType.SOCKET_BIND, null, false))
        .isInstanceOf(java.net.BindException.class);
    assertThat(FailureFactory.errno(Errno.EACCES, OperationType.SOCKET_BIND, null, false))
        .isInstanceOf(java.net.BindException.class)
        .hasMessage("Permission denied");
    assertThat(FailureFactory.errno(Errno.EMFILE, OperationType.SOCKET_ACCEPT, null, false))
        .isExactlyInstanceOf(java.io.IOException.class);
    assertThat(FailureFactory.errno(Errno.ENFILE, OperationType.SOCKET_CONNECT, null, false))
        .isExactlyInstanceOf(java.net.SocketException.class);
    assertThat(FailureFactory.errno(Errno.ENOBUFS, OperationType.SOCKET_WRITE, null, false))
        .isExactlyInstanceOf(java.net.SocketException.class)
        .hasMessage("No buffer space available");
    assertThat(FailureFactory.errno(Errno.EAGAIN, OperationType.THREAD_START, null, false))
        .isInstanceOf(OutOfMemoryError.class)
        .hasMessageStartingWith("unable to create native thread");
  }

  /**
   * Reads, writes, truncates, maps and closes act on an open descriptor: the JDK reports their
   * error as {@code IOException} with the system's text, not as a {@code java.nio.file} exception
   * naming a path.
   */
  @Nested
  @DisplayName("descriptor operations throw IOException")
  class DescriptorOperationsThrowIOException {

    @ParameterizedTest(name = "{1} on {0}")
    @CsvSource({
      "FILE_IO_READ, EIO",
      "FILE_IO_WRITE, ENOSPC",
      "FILE_IO_WRITE, EDQUOT",
      "FILE_IO_TRUNCATE, EIO",
      "FILE_IO_MAP, EACCES",
      "FILE_IO_CLOSE, EIO"
    })
    @DisplayName("the error is an IOException with the system's text")
    void descriptorErrorIsIOException(final OperationType operation, final Errno errno) {
      activate(ChaosSelector.fileIo(Set.of(operation)), errno);

      assertThatThrownBy(
              () ->
                  runtime.beforeFileIo(
                      operation.name(),
                      new EffectReachesTheCallTest.ProbeFileChannelImpl(),
                      "/var/lib/app/wal.log",
                      512))
          .isExactlyInstanceOf(java.io.IOException.class)
          .hasMessage(errno.reason());
    }

    @ParameterizedTest(name = "{1} on {0}")
    @CsvSource({
      "FILE_IO_READ, ENOENT",
      "FILE_IO_READ, EACCES",
      "FILE_IO_WRITE, ENOENT",
      "FILE_IO_TRUNCATE, EROFS",
      "FILE_IO_CLOSE, EACCES",
      "FILE_IO_DELETE, ENOSPC",
      "FILE_IO_OPEN, EIO"
    })
    @DisplayName("an error the kernel never reports for the operation is refused at activation")
    void errorTheKernelNeverReportsIsRefused(final OperationType operation, final Errno errno) {
      assertThatThrownBy(() -> activate(ChaosSelector.fileIo(Set.of(operation)), errno))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining(operation.name());
    }
  }
}
