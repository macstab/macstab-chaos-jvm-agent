package com.macstab.chaos.jvm.micronaut;

import static com.macstab.chaos.jvm.micronaut.ContextWorker.currentSessionId;
import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.testkit.WithChaos;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * With {@code rebuildContext}, Micronaut starts a new application context before each test, in its
 * {@code beforeEach}, on the thread that runs the class's tests. That context and a long-lived
 * thread a bean starts while it starts run outside the class's session; the test methods run in it.
 */
@MicronautTest(rebuildContext = true)
@ExtendWith(ChaosMicronautExtension.class)
@Property(name = "macstab.chaos.test.enabled", value = "true")
@Property(name = ContextWorker.ENABLED, value = "true")
@WithChaos(
    """
    {"id": "mn-rebuilt-context-start", "scope": "SESSION",
     "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@DisplayName("A context @MicronautTest(rebuildContext = true) rebuilds for each chaos test")
class RebuiltContextStartOutsideSessionTest {

  @Inject ContextWorker worker;

  @RepeatedTest(2)
  @DisplayName("starts its threads outside the class's session; the test method runs in it")
  void startsOutsideTheSession(final ChaosSession session) throws Exception {
    assertThat(worker.sessionOfTheWorker.get(10, TimeUnit.SECONDS))
        .as("the session of the worker thread the rebuilt context started")
        .isEmpty();
    assertThat(currentSessionId()).as("the test method's session").contains(session.id());
  }
}
