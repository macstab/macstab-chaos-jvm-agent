package com.macstab.chaos.jvm.micronaut;

import static com.macstab.chaos.jvm.micronaut.ContextWorker.currentSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The application context of a {@link MicronautChaosTest} class starts outside the class's session:
 * Micronaut's extension, registered first by the annotation, starts it in its own {@code beforeAll}
 * before the chaos extension opens the session, and the chaos extension unbinds the opening thread
 * at once, so a long-lived thread a bean starts while the context starts does not run in the
 * session of the class. The test and lifecycle methods run in it.
 */
@MicronautChaosTest
@Property(name = ContextWorker.ENABLED, value = "true")
@WithChaos(
    """
    {"id": "mn-context-start", "scope": "SESSION",
     "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@DisplayName("The application context of a @MicronautChaosTest class")
class MicronautContextStartOutsideSessionTest {

  private static Optional<String> sessionInBeforeAll = Optional.empty();

  @Inject ContextWorker worker;

  private Optional<String> sessionInBeforeEach = Optional.empty();

  @BeforeAll
  static void recordTheSessionOfTheClassSetUp() {
    sessionInBeforeAll = currentSessionId();
  }

  @AfterAll
  static void runsInTheSessionAfterAll(final ChaosSession session) {
    assertThat(currentSessionId()).as("the @AfterAll method's session").contains(session.id());
  }

  @BeforeEach
  void recordTheSessionOfTheSetUp() {
    sessionInBeforeEach = currentSessionId();
  }

  @Test
  @DisplayName("starts its threads outside the class's session; the test methods run in it")
  void startsOutsideTheSession(final ChaosSession session) throws Exception {
    assertThat(worker.sessionOfTheWorker.get(10, TimeUnit.SECONDS))
        .as("the session of the worker thread the context started")
        .isEmpty();
    assertThat(currentSessionId()).as("the test method's session").contains(session.id());
    assertThat(sessionInBeforeEach).as("the @BeforeEach method's session").contains(session.id());
    assertThat(sessionInBeforeAll).as("the @BeforeAll method's session").contains(session.id());
  }

  @RepeatedTest(1)
  @DisplayName("runs a test template in the class's session")
  void templateRunsInTheSession(final ChaosSession session) {
    assertThat(currentSessionId()).contains(session.id());
  }

  @TestFactory
  @DisplayName("runs a test factory and its dynamic tests in the class's session")
  List<DynamicTest> factoryRunsInTheSession(final ChaosSession session) {
    assertThat(currentSessionId()).as("the factory's session").contains(session.id());
    return List.of(
        DynamicTest.dynamicTest(
            "a dynamic test",
            () ->
                assertThat(currentSessionId())
                    .as("the dynamic test's session")
                    .contains(session.id())));
  }

  @AfterEach
  void runsInTheSessionAfterEach(final ChaosSession session) {
    assertThat(currentSessionId()).as("the @AfterEach method's session").contains(session.id());
  }
}
