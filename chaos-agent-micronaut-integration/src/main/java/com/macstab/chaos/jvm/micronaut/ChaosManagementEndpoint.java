package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.MediaType;
import io.micronaut.management.endpoint.annotation.Delete;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;
import io.micronaut.management.endpoint.annotation.Write;
import jakarta.annotation.PreDestroy;

/**
 * The {@code /chaos} management endpoint for Micronaut, matching the Spring Boot actuator's {@code
 * /actuator/chaos}:
 *
 * <ul>
 *   <li>{@code GET /chaos} — the diagnostics snapshot;
 *   <li>{@code POST /chaos} with {@code {"planJson": "<plan>"}} — activates a plan;
 *   <li>{@code DELETE /chaos/{id}} — stops a plan activated here, by handle id or plan name;
 *   <li>{@code DELETE /chaos} — stops every plan activated here.
 * </ul>
 *
 * <p>Off unless {@code macstab.chaos.management.enabled=true}, and sensitive by default: anyone who
 * reaches it can inject faults. Responses are JSON strings, so no serde introspection is needed.
 */
@Endpoint(id = "chaos", defaultSensitive = true)
@Requires(classes = Endpoint.class)
@Requires(property = "macstab.chaos.management.enabled", value = "true")
public class ChaosManagementEndpoint {

  private final ChaosManagement management;

  /**
   * Creates the endpoint.
   *
   * @param controlPlane the control plane it acts on
   */
  public ChaosManagementEndpoint(final ChaosControlPlane controlPlane) {
    this.management = new ChaosManagement(controlPlane);
  }

  /**
   * Returns the diagnostics snapshot.
   *
   * @return the snapshot as JSON
   */
  @Read(produces = MediaType.APPLICATION_JSON)
  public String snapshot() {
    return management.handle("GET", "", null).json();
  }

  /**
   * Activates a plan.
   *
   * @param planJson the plan, in the startup-config JSON format
   * @return the outcome as JSON: {@code status}, {@code handleId}, {@code message}
   */
  @Write(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
  public String activate(final String planJson) {
    return management.handle("POST", "", planJson).json();
  }

  /**
   * Stops a plan activated through this endpoint.
   *
   * @param id the plan's handle id ({@code plan:<name>}) or its name
   * @return the outcome as JSON
   */
  @Delete(produces = MediaType.APPLICATION_JSON)
  public String stop(@Selector final String id) {
    return management.handle("DELETE", "/" + id, null).json();
  }

  /**
   * Stops every plan activated through this endpoint.
   *
   * @return the number stopped, as JSON
   */
  @Delete(produces = MediaType.APPLICATION_JSON)
  public String stopAll() {
    return management.handle("DELETE", "", null).json();
  }

  /** Stops the plans activated here when the context closes. */
  @PreDestroy
  public void close() {
    management.close();
  }
}
