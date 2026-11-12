package com.macstab.chaos.jvm.examples.quarkus;

import static com.macstab.chaos.jvm.examples.quarkus.StartupWorker.currentSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.quarkus.ChaosQuarkusExtension;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A test that composes the chaos extension before {@code @QuarkusTest} registers it first, so
 * Quarkus starts the application in its {@code beforeAll} after the chaos extension opened the
 * class's session on the same thread. The application and a long-lived thread a bean starts while
 * it starts run outside the session all the same; the test runs in it. The test profile of its own
 * makes Quarkus start an application for this class, whichever class ran before it.
 */
@ExtendWith(ChaosQuarkusExtension.class)
@QuarkusTest
@TestProfile(ComposedQuarkusStartOutsideSessionTest.WorkerProfile.class)
@WithChaos(
    """
    {"id": "qk-composed-app-start", "scope": "SESSION",
     "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@DisplayName("The application of a test registering the chaos extension before @QuarkusTest")
class ComposedQuarkusStartOutsideSessionTest {

  @Inject StartupWorker worker;

  @Inject ChaosControlPlane controlPlane;

  @Test
  @DisplayName("starts its threads outside the class's session; the test runs in it")
  void startsOutsideTheSession() throws Exception {
    assertThat(worker.sessionOfTheWorker())
        .as("the session of the worker thread the application started")
        .isEmpty();
    assertThat(currentSessionId(controlPlane)).as("the test method's session").isPresent();
  }

  /** Starts the worker, in an application of this class's own. */
  public static class WorkerProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(StartupWorker.ENABLED, "true");
    }
  }
}
