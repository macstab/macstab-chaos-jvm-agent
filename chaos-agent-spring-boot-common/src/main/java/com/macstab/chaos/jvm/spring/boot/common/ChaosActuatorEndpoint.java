package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/**
 * Spring Boot Actuator endpoint for operating the chaos agent at runtime, with the operations,
 * outcomes and HTTP statuses of the Micronaut and Quarkus endpoints ({@link ChaosManagement}).
 *
 * <p>Exposed at {@code /actuator/chaos} when {@code macstab.chaos.actuator.enabled=true} comes from
 * a trusted property source. The endpoint is authenticated by whatever web security the host
 * application applies to the Actuator namespace; it must not be published unauthenticated. Only
 * plans activated through it can be stopped through it, and they stop when the application context
 * closes.
 */
@Endpoint(id = "chaos")
public class ChaosActuatorEndpoint implements AutoCloseable {

  private final ChaosManagement management;

  /**
   * Creates the endpoint over {@code controlPlane}.
   *
   * @param controlPlane the chaos control plane the operations act on
   */
  public ChaosActuatorEndpoint(final ChaosControlPlane controlPlane) {
    this.management = new ChaosManagement(controlPlane);
  }

  /**
   * Returns a snapshot of the chaos diagnostics at request time.
   *
   * @return the diagnostics snapshot
   */
  @ReadOperation
  public ChaosDiagnostics.Snapshot snapshot() {
    return management.snapshot();
  }

  /**
   * Activates a chaos plan supplied inline in the request body ({@code {"planJson": "<plan>"}}).
   *
   * @param planJson the plan, in the startup-config JSON format
   * @return the outcome, with its HTTP status
   */
  @WriteOperation
  public WebEndpointResponse<ChaosManagement.ActivationResponse> activate(final String planJson) {
    final ChaosManagement.ActivationResponse response = management.activate(planJson);
    return new WebEndpointResponse<>(response, ChaosManagement.httpStatus(response.status()));
  }

  /**
   * Stops a plan activated through this endpoint ({@code DELETE /actuator/chaos/{id}}).
   *
   * @param id the plan's handle id ({@code plan:<name>}) or its name
   * @return the outcome, with its HTTP status
   */
  @DeleteOperation
  public WebEndpointResponse<ChaosManagement.StopResponse> stop(@Selector final String id) {
    final ChaosManagement.StopResponse response = management.stop(id);
    return new WebEndpointResponse<>(response, ChaosManagement.httpStatus(response.status()));
  }

  /**
   * Stops every plan activated through this endpoint ({@code DELETE /actuator/chaos}).
   *
   * @return how many plans were stopped
   */
  @DeleteOperation
  public ChaosManagement.StopAllResponse stopAll() {
    return management.stopAll();
  }

  /** Stops the plans activated through this endpoint; Spring calls it when the context closes. */
  @Override
  public void close() {
    management.close();
  }
}
