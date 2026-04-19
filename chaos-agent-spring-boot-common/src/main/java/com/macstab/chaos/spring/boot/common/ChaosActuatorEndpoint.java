package com.macstab.chaos.spring.boot.common;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.startup.ChaosPlanMapper;
import com.macstab.chaos.startup.ConfigLoadException;
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
    } catch (final IllegalStateException | IllegalArgumentException activationFailure) {
      // Scope conflicts ("scenario key already active"), selector/effect validation mismatches,
      // or feature-unsupported errors surface here. Same redaction rationale as the parse path:
      // a plan-author mistake must not leak the library's internal exception hierarchy to the
      // HTTP client.
      LOGGER.log(Level.WARNING, activationFailure, () -> "chaos-agent: plan activation rejected");
      return new ActivationResponse("error", null, "plan rejected");
    }
  }

  /**
   * Stops a specific scenario by its scenario ID.
   *
   * @param scenarioId scenario identifier
   * @return summary of the stop outcome
   */
  @DeleteOperation
  public StopResponse stop(@Selector final String scenarioId) {
    final boolean stopped = handleRegistry.stop(scenarioId);
    if (stopped) {
      return new StopResponse("stopped", scenarioId, null);
    }
    if (controlPlane.diagnostics().scenario(scenarioId).isPresent()) {
      return new StopResponse(
          "unmanaged",
          scenarioId,
          "scenario is registered but not managed by this starter; stop via its activation handle");
    }
    return new StopResponse("not-found", scenarioId, "no scenario with id " + scenarioId);
  }

  /**
   * Stops all JVM-scoped scenarios that were activated through this starter.
   *
   * @return summary of the bulk-stop outcome
   */
  @WriteOperation
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
