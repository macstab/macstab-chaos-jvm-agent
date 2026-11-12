package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.WithChaos;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application context of a {@link ChaosTest} class starts outside the class's session: a
 * long-lived thread a bean starts while the context starts (an event loop, a consumer, a worker
 * loop) serves the whole application, so it must not run in the session of whichever class happened
 * to start the context. The test and lifecycle methods still run in the session.
 */
@ChaosTest(classes = ContextStartOutsideSessionTest.App.class)
@WithChaos(
    """
    {"id": "sb3-context-start", "scope": "SESSION",
     "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@DisplayName("The application context of a @ChaosTest class")
class ContextStartOutsideSessionTest {

  private static Optional<String> sessionInBeforeAll = Optional.empty();

  @Autowired Worker worker;

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

  private static Optional<String> currentSessionId() {
    return Optional.ofNullable(
        ChaosPlatform.current().diagnostics().snapshot().runtimeDetails().get("currentSessionId"));
  }

  /** The test application: one bean that starts a long-lived thread when the context starts. */
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {
    @Bean(destroyMethod = "stop")
    Worker worker() {
      return new Worker();
    }
  }

  /** Starts its thread in its constructor; the thread records its session and waits for stop. */
  static class Worker {
    final CompletableFuture<Optional<String>> sessionOfTheWorker = new CompletableFuture<>();
    private final Thread thread;

    Worker() {
      thread =
          new Thread(
              () -> {
                sessionOfTheWorker.complete(currentSessionId());
                try {
                  Thread.sleep(Long.MAX_VALUE);
                } catch (final InterruptedException stopped) {
                  Thread.currentThread().interrupt();
                }
              },
              "sb3-context-worker");
      thread.setDaemon(true);
      thread.start();
    }

    void stop() {
      thread.interrupt();
    }
  }
}
