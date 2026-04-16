package io.macstab.chaos.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.macstab.chaos.api.ChaosControlPlane;
import io.macstab.chaos.api.ChaosSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosTestKit")
class ChaosTestKitTest {

  @Nested
  @DisplayName("install")
  class Install {

    @Test
    @DisplayName("returns non-null ChaosControlPlane")
    void returnsNonNull() {
      assertThat(ChaosTestKit.install()).isNotNull();
    }

    @Test
    @DisplayName("is idempotent — repeated calls return the same instance")
    void isIdempotent() {
      final ChaosControlPlane first = ChaosTestKit.install();
      final ChaosControlPlane second = ChaosTestKit.install();
      assertThat(first).isSameAs(second);
    }
  }

  @Nested
  @DisplayName("openSession")
  class OpenSession {

    @Test
    @DisplayName("returns non-null session with matching display name")
    void returnsSessionWithMatchingDisplayName() {
      final ChaosSession session = ChaosTestKit.openSession("my-test");
      try {
        assertThat(session).isNotNull();
        assertThat(session.displayName()).isEqualTo("my-test");
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("session id is non-null")
    void sessionIdIsNonNull() {
      final ChaosSession session = ChaosTestKit.openSession("id-test");
      try {
        assertThat(session.id()).isNotNull();
      } finally {
        session.close();
      }
    }

    @Test
    @DisplayName("session closes cleanly without throwing")
    void sessionClosesCleanly() {
      final ChaosSession session = ChaosTestKit.openSession("close-test");
      assertThatCode(session::close).doesNotThrowAnyException();
    }
  }
}
