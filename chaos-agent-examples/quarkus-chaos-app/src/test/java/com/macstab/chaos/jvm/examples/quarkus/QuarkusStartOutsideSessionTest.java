package com.macstab.chaos.jvm.examples.quarkus;

import static com.macstab.chaos.jvm.examples.quarkus.StartupWorker.currentSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.quarkus.QuarkusChaosTest;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The application of a {@link QuarkusChaosTest} class starts outside the class's session: the
 * annotation registers Quarkus's test extension first, which starts the application in its {@code
 * beforeAll} before the chaos extension opens the session, and the chaos extension unbinds the
 * opening thread at once. A long-lived thread a bean starts while the application starts does not
 * run in the session of the class; the test runs in it. The test profile of its own makes Quarkus
 * start an application for this class, whichever class ran before it.
 */
@QuarkusChaosTest
@TestProfile(QuarkusStartOutsideSessionTest.WorkerProfile.class)
@WithChaos(
    """
    {"id": "qk-app-start", "scope": "SESSION",
     "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@DisplayName("The application of a @QuarkusChaosTest class")
class QuarkusStartOutsideSessionTest {

  @Inject StartupWorker worker;

  @Inject ChaosControlPlane controlPlane;

  private Optional<String> sessionInBeforeEach = Optional.empty();

  @BeforeEach
  void recordTheSessionOfTheSetUp() {
    sessionInBeforeEach = currentSessionId(controlPlane);
  }

  @Test
  @DisplayName("starts its threads outside the class's session; the test runs in it")
  void startsOutsideTheSession() throws Exception {
    assertThat(worker.sessionOfTheWorker())
        .as("the session of the worker thread the application started")
        .isEmpty();
    final Optional<String> session = currentSessionId(controlPlane);
    assertThat(session).as("the test method's session").isPresent();
    assertThat(sessionInBeforeEach).as("the @BeforeEach method's session").isEqualTo(session);
  }

  /** Starts the worker, in an application of this class's own. */
  public static class WorkerProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(StartupWorker.ENABLED, "true");
    }
  }
}
