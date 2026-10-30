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
import org.junit.jupiter.api.Test;

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
  @DisplayName("ETIMEDOUT is a ConnectException on connect")
  void timeoutOnConnect() {
    assertThat(FailureFactory.errno(Errno.ETIMEDOUT, OperationType.SOCKET_CONNECT, null))
        .isExactlyInstanceOf(java.net.ConnectException.class);
    assertThat(FailureFactory.errno(Errno.ETIMEDOUT, OperationType.SOCKET_READ, null))
        .isExactlyInstanceOf(java.net.SocketTimeoutException.class);
  }

  @Test
  @DisplayName("file errors carry the file's path")
  void fileErrorsCarryPath() {
    activate(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_WRITE)), Errno.ENOSPC);

    assertThatThrownBy(
            () -> runtime.beforeFileIo("FILE_IO_WRITE", this, "/var/lib/app/wal.log", 512))
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
  @DisplayName("EACCES and ENOENT use the specific java.nio.file exceptions")
  void specificFileExceptions() {
    assertThat(FailureFactory.errno(Errno.EACCES, OperationType.FILE_IO_READ, "/x"))
        .isExactlyInstanceOf(AccessDeniedException.class);
    assertThat(FailureFactory.errno(Errno.ENOENT, OperationType.FILE_IO_READ, "/x"))
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
}
