package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.SyncFailedException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
      assertThat(FailureFactory.reject(OperationType.JNDI_LOOKUP, "no name"))
          .isInstanceOf(javax.naming.NamingException.class)
          .hasMessage("no name");
    }

    @Test
    @DisplayName("a JMX call fails with MBeanException carrying the message")
    void jmx() {
      assertThat(FailureFactory.reject(OperationType.JMX_INVOKE, "no bean"))
          .isInstanceOf(javax.management.MBeanException.class)
          .hasMessageContaining("no bean");
    }

    @Test
    @DisplayName("a TLS handshake fails with SSLHandshakeException")
    void ssl() {
      assertThat(FailureFactory.reject(OperationType.SSL_HANDSHAKE, "bad cert"))
          .isInstanceOf(javax.net.ssl.SSLHandshakeException.class)
          .hasMessage("bad cert");
    }

    @Test
    @DisplayName("a JDBC call fails with SQLException")
    void jdbc() {
      assertThat(FailureFactory.reject(OperationType.JDBC_STATEMENT_EXECUTE, "db down"))
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
    @DisplayName("a java.nio close names the path")
    void nioClose() {
      assertThat(
              FailureFactory.errno(
                  ChaosEffect.Errno.ENOENT, OperationType.FILE_IO_CLOSE, "/x", false))
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
}
