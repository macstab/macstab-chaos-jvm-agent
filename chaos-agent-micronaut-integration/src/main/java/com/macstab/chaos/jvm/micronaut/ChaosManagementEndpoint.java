package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.management.endpoint.annotation.Delete;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;
import io.micronaut.management.endpoint.annotation.Selector;
import io.micronaut.management.endpoint.annotation.Write;
import jakarta.annotation.PreDestroy;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
 * reaches it can inject faults. It uses the agent if one is installed and never installs it (503
 * otherwise); answers carry the operation's status (400 invalid, 404 not found, 409 conflict, 422
 * rejected) and a JSON body.
 */
@Endpoint(id = "chaos", defaultSensitive = true)
@Requires(classes = Endpoint.class)
@Requires(property = "macstab.chaos.management.enabled", value = "true")
public class ChaosManagementEndpoint {

  private final Supplier<ChaosControlPlane> controlPlane;
  private volatile @Nullable ChaosManagement management;

  /** Creates the endpoint for the installed agent. */
  public ChaosManagementEndpoint() {
    this(ChaosPlatform::current);
  }

  /**
   * Creates the endpoint for a control plane.
   *
   * @param controlPlane supplies the control plane; throws {@link IllegalStateException} when no
   *     agent is installed
   */
  ChaosManagementEndpoint(final Supplier<ChaosControlPlane> controlPlane) {
    this.controlPlane = controlPlane;
  }

  /**
   * Returns the diagnostics snapshot.
   *
   * @return the snapshot as JSON
   */
  @Read(produces = MediaType.APPLICATION_JSON)
  public HttpResponse<String> snapshot() {
    return answer("GET", "", null);
  }

  /**
   * Activates a plan.
   *
   * @param planJson the plan, in the startup-config JSON format
   * @return the outcome: {@code status}, {@code handleId}, {@code message}
   */
  @Write(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
  public HttpResponse<String> activate(final String planJson) {
    return answer("POST", "", planJson);
  }

  /**
   * Stops a plan activated through this endpoint.
   *
   * @param id the plan's handle id ({@code plan:<name>}) or its name
   * @return the outcome
   */
  @Delete(produces = MediaType.APPLICATION_JSON)
  public HttpResponse<String> stop(@Selector final String id) {
    return answer("DELETE", "/" + id, null);
  }

  /**
   * Stops every plan activated through this endpoint.
   *
   * @return the number stopped
   */
  @Delete(produces = MediaType.APPLICATION_JSON)
  public HttpResponse<String> stopAll() {
    return answer("DELETE", "", null);
  }

  private HttpResponse<String> answer(
      final String method, final String path, final @Nullable String body) {
    final ChaosManagement.HttpResult result;
    try {
      result = management().handle(method, path, body);
    } catch (final IllegalStateException notInstalled) {
      return HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE)
          .body("{\"status\":\"error\",\"message\":\"the chaos agent is not installed\"}");
    }
    return HttpResponse.<String>status(HttpStatus.valueOf(result.status())).body(result.json());
  }

  private ChaosManagement management() {
    ChaosManagement current = management;
    if (current == null) {
      synchronized (this) {
        current = management;
        if (current == null) {
          current = new ChaosManagement(controlPlane.get());
          management = current;
        }
      }
    }
    return current;
  }

  /** Stops the plans activated here when the context closes. */
  @PreDestroy
  public void close() {
    final ChaosManagement current = management;
    if (current != null) {
      current.close();
    }
  }
}
