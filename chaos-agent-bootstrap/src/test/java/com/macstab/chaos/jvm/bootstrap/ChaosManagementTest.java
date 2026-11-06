package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosManagement")
class ChaosManagementTest {

  private static final String PLAN =
      """
      {"metadata":{"name":"mgmt"},"scenarios":[{"id":"mgmt-delay","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final ChaosManagement management = new ChaosManagement(runtime);

  @AfterEach
  void tearDown() {
    management.close();
    runtime.close();
  }

  private ChaosDiagnostics.ScenarioState state(final String id) {
    return runtime
        .diagnostics()
        .scenario(id)
        .map(ChaosDiagnostics.ScenarioReport::state)
        .orElse(null);
  }

  @Test
  @DisplayName("activates a plan and stops it by handle id or plan name")
  void activateAndStop() {
    final ChaosManagement.ActivationResponse activated = management.activate(PLAN);
    assertThat(activated.status()).isEqualTo("activated");
    assertThat(activated.handleId()).isEqualTo("plan:mgmt");
    assertThat(state("mgmt-delay")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    assertThat(management.stop("mgmt").status()).isEqualTo("stopped");
    assertThat(state("mgmt-delay")).isNull();
    assertThat(management.stop("mgmt").status()).isEqualTo("not-found");

    management.activate(PLAN);
    assertThat(management.stop("plan:mgmt").status()).isEqualTo("stopped");
  }

  @Test
  @DisplayName("rejects blank, malformed and invalid plans with a reason")
  void rejects() {
    assertThat(management.activate(" ").status()).isEqualTo("invalid");
    assertThat(management.activate("{ broken").message()).startsWith("invalid plan");
    management.activate(PLAN);
    final ChaosManagement.ActivationResponse conflict =
        management.activate(PLAN.replace("mgmt\"}", "other\"}"));
    assertThat(conflict.status()).isEqualTo("conflict");
    assertThat(conflict.message()).contains("already active");
  }

  @Test
  @DisplayName("leaves scenarios activated elsewhere alone, and stops all of its own")
  void unmanagedAndStopAll() {
    runtime.activate(
        ChaosScenario.builder("app-owned")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build());
    assertThat(management.stop("app-owned").status()).isEqualTo("unmanaged");

    management.activate(PLAN);
    assertThat(management.stopAll().stoppedCount()).isEqualTo(1);
    assertThat(state("app-owned")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("serves the HTTP operations with status codes and JSON bodies")
  void http() {
    final ChaosManagement.HttpResult activated = management.handle("POST", "", PLAN);
    assertThat(activated.status()).isEqualTo(200);
    assertThat(activated.json()).contains("\"handleId\":\"plan:mgmt\"");

    final ChaosManagement.HttpResult snapshot = management.handle("GET", "/", null);
    assertThat(snapshot.status()).isEqualTo(200);
    assertThat(snapshot.json()).contains("\"id\":\"mgmt-delay\"").contains("\"state\":\"ACTIVE\"");

    assertThat(management.handle("POST", "", "{ broken").status()).isEqualTo(400);
    assertThat(management.handle("DELETE", "/nope", null).status()).isEqualTo(404);
    assertThat(management.handle("POST", "", PLAN.replace("mgmt\"}", "again\"}")).status())
        .as("conflict")
        .isEqualTo(409);
    assertThat(management.handle("DELETE", "/plan%3Amgmt", null).status())
        .as("decoded")
        .isEqualTo(200);
    assertThat(management.handle("DELETE", "", null).json()).contains("\"stoppedCount\":0");
    assertThat(management.handle("PUT", "", null).status()).isEqualTo(405);
    assertThat(management.handle("GET", "/x", null).status()).isEqualTo(404);
    assertThat(management.handle("POST", "/x", PLAN).status()).isEqualTo(404);
  }

  @Test
  @DisplayName("a plan the runtime cannot run is invalid (400), with the runtime's reason")
  void semanticallyInvalidPlans() {
    final ChaosManagement.ActivationResponse unsupported =
        management.activate(
            PLAN.replace(
                "{\"type\":\"delay\",\"minDelay\":\"PT0.001S\",\"maxDelay\":\"PT0.001S\"}",
                "{\"type\":\"tornWrite\"}"));
    assertThat(unsupported.status()).isEqualTo("invalid");
    assertThat(unsupported.message()).startsWith("invalid plan").contains("TornWriteEffect");

    final ChaosManagement.ActivationResponse sessionScoped =
        management.activate(PLAN.replace("\"scope\":\"JVM\"", "\"scope\":\"SESSION\""));
    assertThat(sessionScoped.status()).as("not a conflict").isEqualTo("invalid");
    assertThat(sessionScoped.message()).contains("session");
    assertThat(management.handle("POST", "", PLAN.replace("JVM", "SESSION")).status())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("any other activation failure is rejected (422); a stale handle is replaced")
  void otherFailuresAndReplacement() {
    final java.util.concurrent.atomic.AtomicInteger activations =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final com.macstab.chaos.jvm.api.ChaosControlPlane scripted =
        (com.macstab.chaos.jvm.api.ChaosControlPlane)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {com.macstab.chaos.jvm.api.ChaosControlPlane.class},
                (proxy, method, args) -> {
                  if (!method.getName().equals("activate")) {
                    return method.isDefault()
                        ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args)
                        : null;
                  }
                  return switch (activations.incrementAndGet()) {
                    case 1 -> throw new IllegalStateException("agent busy");
                    case 2 -> handle("plan:mgmt", closed, ChaosDiagnostics.ScenarioState.STOPPED);
                    default -> handle("plan:mgmt", closed);
                  };
                });
    final ChaosManagement scriptedManagement = new ChaosManagement(scripted);

    final ChaosManagement.ActivationResponse busy = scriptedManagement.activate(PLAN);
    assertThat(busy.status()).isEqualTo("rejected");
    assertThat(busy.message())
        .as("the runtime's own message stays in the log")
        .isEqualTo("plan rejected: the runtime could not activate it (see the log)");
    assertThat(scriptedManagement.handle("PATCH", null, null).status()).isEqualTo(405);
    assertThat(scriptedManagement.handle(null, null, null).status()).isEqualTo(405);

    scriptedManagement.activate(PLAN);
    assertThat(scriptedManagement.activate(PLAN).status()).isEqualTo("activated");
    assertThat(closed).as("the finished plan it replaced is closed").hasValue(1);
  }

  @Test
  @DisplayName("a plan named like a running plan is a conflict and leaves the running plan alone")
  void runningPlanNameIsAConflict() {
    management.activate(PLAN);

    final ChaosManagement.ActivationResponse second =
        management.activate(PLAN.replace("mgmt-delay", "other-delay"));

    assertThat(second.status()).isEqualTo("conflict");
    assertThat(runtime.diagnostics().scenario("mgmt-delay").orElseThrow().state())
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThat(runtime.diagnostics().scenario("other-delay")).isEmpty();
  }

  @Test
  @DisplayName("two plans without a name: the second is a 409 conflict, not a replacement")
  void namelessPlansConflict() {
    final String nameless = PLAN.replace("\"metadata\":{\"name\":\"mgmt\"},", "");
    assertThat(management.handle("POST", "", nameless).status()).isEqualTo(200);

    assertThat(
            management.handle("POST", "", nameless.replace("mgmt-delay", "other-delay")).status())
        .isEqualTo(409);
    assertThat(runtime.diagnostics().scenario("mgmt-delay")).isPresent();
  }

  @Test
  @DisplayName("of two activations of one name racing, the second to claim it is refused")
  void racingActivationsOfOneName() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<ChaosManagement> self =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicReference<ChaosManagement.ActivationResponse> inner =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.atomic.AtomicInteger activations =
        new java.util.concurrent.atomic.AtomicInteger();
    // The first activation passes the name check; while it runs, a second one of the same name
    // completes and claims the name first.
    final ChaosManagement racing =
        new ChaosManagement(
            scriptedControlPlane(
                () -> {
                  if (activations.incrementAndGet() == 1) {
                    inner.set(self.get().activate(PLAN));
                  }
                  return handle("plan:mgmt", closed);
                }));
    self.set(racing);

    final ChaosManagement.ActivationResponse outer = racing.activate(PLAN);

    assertThat(inner.get().status()).isEqualTo("activated");
    assertThat(outer.status()).isEqualTo("conflict");
    assertThat(closed).as("the refused plan is stopped again, the running one kept").hasValue(1);
  }

  @Test
  @DisplayName("a plan that expired (INACTIVE) is replaced like a stopped one")
  void expiredPlanIsReplaced() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.Iterator<com.macstab.chaos.jvm.api.ChaosActivationHandle> plans =
        java.util.List.of(
                handle("plan:mgmt", closed, ChaosDiagnostics.ScenarioState.INACTIVE),
                handle("plan:mgmt", closed))
            .iterator();
    final ChaosManagement replacing = new ChaosManagement(scriptedControlPlane(plans::next));
    replacing.activate(PLAN);

    assertThat(replacing.activate(PLAN).status()).isEqualTo("activated");
    assertThat(closed).as("the expired plan is closed").hasValue(1);
  }

  @Test
  @DisplayName("a null plan is invalid; a rejection is 422 and an unmanaged stop 409 over HTTP")
  void nullPlanAndHttpStatuses() {
    assertThat(management.activate(null).status()).isEqualTo("invalid");

    final ChaosManagement rejecting =
        new ChaosManagement(
            scriptedControlPlane(
                () -> {
                  throw new IllegalStateException("agent busy");
                }));
    final ChaosManagement.HttpResult rejected = rejecting.handle("POST", "", PLAN);
    assertThat(rejected.status()).isEqualTo(422);
    assertThat(rejected.json()).contains("\"status\":\"rejected\"");

    runtime.activate(
        ChaosScenario.builder("app-owned")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build());
    final ChaosManagement.HttpResult unmanaged = management.handle("DELETE", "/app-owned", null);
    assertThat(unmanaged.status()).isEqualTo(409);
    assertThat(unmanaged.json()).contains("\"status\":\"unmanaged\"");
  }

  @Test
  @DisplayName("an activation failure without a message is invalid, not a conflict")
  void activationFailureWithoutMessage() {
    final ChaosManagement failing =
        new ChaosManagement(
            scriptedControlPlane(
                () -> {
                  throw new com.macstab.chaos.jvm.api.ChaosActivationException(null);
                }));

    final ChaosManagement.ActivationResponse response = failing.activate(PLAN);

    assertThat(response.status()).isEqualTo("invalid");
    assertThat(response.message()).isEqualTo("plan rejected: null");
  }

  @Test
  @DisplayName("a plan whose activation completes after close() is stopped again at once")
  void activationAfterCloseIsStopped() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final ChaosManagement closing =
        new ChaosManagement(scriptedControlPlane(() -> handle("plan:late", closed)));
    closing.close();

    final ChaosManagement.ActivationResponse response = closing.activate(PLAN);

    assertThat(response.status()).isEqualTo("rejected");
    assertThat(closed).hasValue(1);
  }

  @Test
  @DisplayName("a plan stopped by a close() during its own activation is answered as rejected")
  void closeDuringActivationIsRejected() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<ChaosManagement> self =
        new java.util.concurrent.atomic.AtomicReference<>();
    // Replacing the first plan closes it; that close() closes the endpoint, which then stops the
    // second plan before its activation returns.
    final com.macstab.chaos.jvm.api.ChaosActivationHandle first =
        (com.macstab.chaos.jvm.api.ChaosActivationHandle)
            java.lang.reflect.Proxy.newProxyInstance(
                ChaosManagementTest.class.getClassLoader(),
                new Class<?>[] {com.macstab.chaos.jvm.api.ChaosActivationHandle.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "id" -> "plan:mgmt";
                      case "state" -> ChaosDiagnostics.ScenarioState.STOPPED;
                      case "close" -> {
                        self.get().close();
                        yield null;
                      }
                      case "equals" -> proxy == args[0];
                      case "hashCode" -> System.identityHashCode(proxy);
                      default -> null;
                    });
    final java.util.Iterator<com.macstab.chaos.jvm.api.ChaosActivationHandle> plans =
        java.util.List.of(first, handle("plan:mgmt", closed)).iterator();
    final ChaosManagement racing = new ChaosManagement(scriptedControlPlane(plans::next));
    self.set(racing);
    racing.activate(PLAN);

    final ChaosManagement.ActivationResponse response = racing.activate(PLAN);

    assertThat(response.status()).isEqualTo("rejected");
    assertThat(closed).as("stopped once, by close()").hasValue(1);
  }

  @Test
  @DisplayName("stopping all plans continues past one that fails to stop")
  void stopAllContinuesPastAFailure() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.Iterator<com.macstab.chaos.jvm.api.ChaosActivationHandle> plans =
        java.util.List.of(failingHandle("plan:a"), handle("plan:b", closed)).iterator();
    final ChaosManagement two = new ChaosManagement(scriptedControlPlane(plans::next));
    two.activate(PLAN);
    two.activate(PLAN);

    assertThat(two.stopAll().stoppedCount()).isEqualTo(1);
    assertThat(closed).hasValue(1);
  }

  @Test
  @DisplayName("an id with a malformed escape is a bad request")
  void malformedIdIsBadRequest() {
    assertThat(management.handle("DELETE", "/%zz", null).status()).isEqualTo(400);
  }

  @Test
  @DisplayName("activating a plan that returns the handle already held does not close it")
  void sameHandleIsKept() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final com.macstab.chaos.jvm.api.ChaosActivationHandle same = handle("plan:mgmt", closed);
    final ChaosManagement idempotent = new ChaosManagement(scriptedControlPlane(() -> same));

    idempotent.activate(PLAN);
    idempotent.activate(PLAN);

    assertThat(closed).as("the held handle is the returned one").hasValue(0);
    assertThat(idempotent.stopAll().stoppedCount()).isEqualTo(1);
    assertThat(closed).hasValue(1);
  }

  @Test
  @DisplayName("stopAll skips a handle that closing another one already stopped")
  void stopAllSkipsHandleStoppedMeanwhile() {
    final java.util.concurrent.atomic.AtomicInteger closed =
        new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<ChaosManagement> owner =
        new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.Iterator<com.macstab.chaos.jvm.api.ChaosActivationHandle> handles =
        java.util.List.of(
                linkedHandle("plan:first", "plan:second", owner, closed),
                linkedHandle("plan:second", "plan:first", owner, closed))
            .iterator();
    final ChaosManagement linked = new ChaosManagement(scriptedControlPlane(handles::next));
    owner.set(linked);
    linked.activate(PLAN);
    linked.activate(PLAN);

    // Closing either handle stops the other through the endpoint: stopAll then finds it gone.
    assertThat(linked.stopAll().stoppedCount()).isEqualTo(1);
    assertThat(closed).as("both plans are stopped").hasValue(2);
    assertThat(linked.stop("plan:first").status()).isEqualTo("not-found");
  }

  private static com.macstab.chaos.jvm.api.ChaosActivationHandle linkedHandle(
      final String id,
      final String partner,
      final java.util.concurrent.atomic.AtomicReference<ChaosManagement> owner,
      final java.util.concurrent.atomic.AtomicInteger closed) {
    final com.macstab.chaos.jvm.api.ChaosActivationHandle counted = handle(id, closed);
    return (com.macstab.chaos.jvm.api.ChaosActivationHandle)
        java.lang.reflect.Proxy.newProxyInstance(
            ChaosManagementTest.class.getClassLoader(),
            new Class<?>[] {com.macstab.chaos.jvm.api.ChaosActivationHandle.class},
            (proxy, method, args) -> {
              if (method.getName().equals("close")) {
                counted.close();
                owner.get().stop(partner);
                return null;
              }
              return method.invoke(counted, args);
            });
  }

  /** A control plane whose {@code activate(plan)} runs {@code activation}; nothing else works. */
  private com.macstab.chaos.jvm.api.ChaosControlPlane scriptedControlPlane(
      final java.util.function.Supplier<com.macstab.chaos.jvm.api.ChaosActivationHandle>
          activation) {
    return (com.macstab.chaos.jvm.api.ChaosControlPlane)
        java.lang.reflect.Proxy.newProxyInstance(
            ChaosManagementTest.class.getClassLoader(),
            new Class<?>[] {com.macstab.chaos.jvm.api.ChaosControlPlane.class},
            (proxy, method, args) -> {
              if (method.getName().equals("activate")) {
                return activation.get();
              }
              if (method.getName().equals("diagnostics")) {
                return runtime.diagnostics();
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static com.macstab.chaos.jvm.api.ChaosActivationHandle handle(
      final String id, final java.util.concurrent.atomic.AtomicInteger closed) {
    return handle(id, closed, ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  private static com.macstab.chaos.jvm.api.ChaosActivationHandle handle(
      final String id,
      final java.util.concurrent.atomic.AtomicInteger closed,
      final ChaosDiagnostics.ScenarioState state) {
    return new com.macstab.chaos.jvm.api.ChaosActivationHandle() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public void start() {}

      @Override
      public void stop() {}

      @Override
      public void release() {}

      @Override
      public ChaosDiagnostics.ScenarioState state() {
        return state;
      }

      @Override
      public long matchedCount() {
        return 0;
      }

      @Override
      public long appliedCount() {
        return 0;
      }

      @Override
      public void close() {
        closed.incrementAndGet();
      }
    };
  }

  /** A handle whose close() fails. */
  private static com.macstab.chaos.jvm.api.ChaosActivationHandle failingHandle(final String id) {
    return (com.macstab.chaos.jvm.api.ChaosActivationHandle)
        java.lang.reflect.Proxy.newProxyInstance(
            ChaosManagementTest.class.getClassLoader(),
            new Class<?>[] {com.macstab.chaos.jvm.api.ChaosActivationHandle.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "id" -> id;
                  case "close" -> throw new IllegalStateException("cannot stop");
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  default -> null;
                });
  }
}
