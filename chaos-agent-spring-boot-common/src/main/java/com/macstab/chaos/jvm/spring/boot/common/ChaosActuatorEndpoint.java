package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Spring Boot Actuator endpoint for operating the chaos agent at runtime.
 *
 * <p>Exposed at {@code /actuator/chaos} when {@code macstab.chaos.actuator.enabled=true}. The
 * endpoint is authenticated by whatever web security the host application applies to the Actuator
 * namespace; it must not be published unauthenticated to the public internet.
 */
@Endpoint(id = "chaos")
public class ChaosActuatorEndpoint {

  private static final Logger LOGGER = Logger.getLogger(ChaosActuatorEndpoint.class.getName());

  /** Key prefix used by the core runtime for JVM-scoped scenario handles. */
  private static final String PLAN_HANDLE_PREFIX = "plan:";

  private final ChaosControlPlane controlPlane;
  private final ChaosHandleRegistry handleRegistry;

  /**
   * Creates a new endpoint backed by the given control plane and handle registry.
   *
   * @param controlPlane the chaos control plane driving the endpoint operations
   * @param handleRegistry registry of activation handles produced by this starter
   */
  public ChaosActuatorEndpoint(
      final ChaosControlPlane controlPlane, final ChaosHandleRegistry handleRegistry) {
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.handleRegistry = Objects.requireNonNull(handleRegistry, "handleRegistry");
  }

  /**
   * Returns a snapshot of the chaos diagnostics at request time.
   *
   * @return the diagnostics snapshot
   */
  @ReadOperation
  public ChaosDiagnostics.Snapshot snapshot() {
    return controlPlane.diagnostics().snapshot();
  }

  /**
   * Activates a chaos plan supplied inline in the request body.
   *
   * @param planJson a JSON-encoded {@link ChaosPlan}
   * @return summary of the activation outcome
   */
  @WriteOperation
  public ActivationResponse activate(final String planJson) {
    if (planJson == null || planJson.isBlank()) {
      return new ActivationResponse("error", null, "plan JSON must not be blank");
    }
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(planJson);
    } catch (final ConfigLoadException | IllegalArgumentException parseFailure) {
      // Log the full cause chain server-side — package layout, Jackson offsets, character
      // positions — but never leak it back in the HTTP response. An authenticated Actuator
      // endpoint still sits behind caches and proxies that may log response bodies, so a raw
      // Jackson error leaks internals to everyone downstream.
      LOGGER.log(Level.WARNING, parseFailure, () -> "chaos-agent: rejecting malformed plan JSON");
      return new ActivationResponse("error", null, "invalid plan");
    }
    try {
      final ChaosActivationHandle handle = controlPlane.activate(plan);
      handleRegistry.register(handle);
      return new ActivationResponse("activated", handle.id(), null);
    } catch (final RuntimeException activationFailure) {
      // Scope conflicts (ChaosActivationException "scenario key already active"), selector/effect
      // validation failures (ChaosValidationException) and unsupported features surface here.
      // Catching every RuntimeException keeps them out of the default 500 error rendering, which
      // would expose stack frames in the response body. Same redaction rationale as the parse path:
      // a plan-author mistake must not leak the library's internal exception hierarchy to the
      // HTTP client.
      LOGGER.log(Level.WARNING, activationFailure, () -> "chaos-agent: plan activation rejected");
      return new ActivationResponse("error", null, "plan rejected");
    }
  }

  /**
   * Stops a plan activated through this endpoint ({@code DELETE /actuator/chaos/{id}}).
   *
   * <p>{@code id} is the {@code handleId} returned by {@link #activate(String)} ({@code
   * plan:<name>}) or the plan name alone. Plans are activated atomically, so an individual scenario
   * of a plan cannot be stopped on its own: a scenario id answers {@code "unmanaged"} with a hint
   * to stop the plan by its handle id. An earlier version looked handles up under {@code
   * jvm::<scenarioId>}, a key this endpoint never registers, so a stop by the plan handle id was
   * the only one that could ever succeed, and nothing told the caller.
   *
   * @param id plan handle id, plan name, or scenario id
   * @return the outcome; never {@code null}
   */
  @DeleteOperation
  public StopResponse stop(@Selector final String id) {
    if (handleRegistry.stop(id) || handleRegistry.stop(PLAN_HANDLE_PREFIX + id)) {
      return new StopResponse("stopped", id, null);
    }
    if (controlPlane.diagnostics().scenario(id).isPresent()) {
      return new StopResponse(
          "unmanaged",
          id,
          "scenario was not activated through this endpoint, or is part of a plan; stop the plan"
              + " by its handle id (plan:<name>), or the scenario via its activation handle");
    }
    return new StopResponse("not-found", id, "no plan or scenario with id " + id);
  }

  /**
   * Stops all JVM-scoped scenarios that were activated through this starter.
   *
   * <p>Mapped to {@code DELETE /actuator/chaos}. Previously this was a {@code @WriteOperation}
   * (POST) alongside {@link #activate(String)}, which Spring Boot Actuator refuses to start with a
   * {@code BeanCreationException}: two {@code @WriteOperation} methods on the same endpoint without
   * distinguishing selectors collide on the same HTTP verb and path. DELETE pairs naturally with
   * the no-selector bulk-stop semantics and is distinct from {@link #stop(String)}, which is a
   * {@code DELETE /actuator/chaos/{scenarioId}} — both are DELETEs but the path segment introduced
   * by {@code @Selector} keeps them routable.
   *
   * @return summary of the bulk-stop outcome
   */
  @DeleteOperation
  public StopAllResponse stopAll() {
    final int stoppedCount = handleRegistry.stopAll();
    return new StopAllResponse(stoppedCount);
  }

  /**
   * Response body for the {@code activate} operation.
   *
   * @param status operation status ({@code activated} or {@code error})
   * @param handleId the handle ID of the activated plan, when successful
   * @param message optional human-readable detail
   */
  public record ActivationResponse(String status, String handleId, String message) {}

  /**
   * Response body for the {@code stop} operation.
   *
   * @param status operation status ({@code stopped}, {@code unmanaged}, {@code not-found})
   * @param scenarioId the scenario ID targeted by the operation
   * @param message optional human-readable detail
   */
  public record StopResponse(String status, String scenarioId, String message) {}

  /**
   * Response body for the {@code stopAll} operation.
   *
   * @param stoppedCount the number of JVM-scoped scenarios that were stopped
   */
  public record StopAllResponse(int stoppedCount) {}
}
