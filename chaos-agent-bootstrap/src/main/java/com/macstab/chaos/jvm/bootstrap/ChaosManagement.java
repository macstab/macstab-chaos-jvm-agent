package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The operations of the chaos management endpoints, independent of any web framework: the Spring
 * actuator's {@code /actuator/chaos}, Micronaut's {@code /chaos} endpoint and Quarkus' {@code
 * /q/chaos} route offer the same four.
 *
 * <ul>
 *   <li>snapshot — the diagnostics: every scenario with its state and counters;
 *   <li>activate — activates a plan given as JSON; the plan's handle id is {@code plan:<name>};
 *   <li>stop — stops a plan activated here, by its handle id or plan name;
 *   <li>stop all — stops every plan activated here.
 * </ul>
 *
 * <p>Only plans activated through this instance can be stopped through it; scenarios activated by
 * the application, a test or a startup plan are listed but left alone. Anyone who can reach an
 * endpoint can inject faults: the endpoints are off by default and must be secured.
 */
public final class ChaosManagement implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(ChaosManagement.class.getName());
  private static final String PLAN_PREFIX = "plan:";

  private final ChaosControlPlane controlPlane;
  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  /**
   * Creates the operations for {@code controlPlane}.
   *
   * @param controlPlane the control plane they act on
   */
  public ChaosManagement(final ChaosControlPlane controlPlane) {
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
  }

  /**
   * Returns the diagnostics snapshot.
   *
   * @return the snapshot
   */
  public ChaosDiagnostics.Snapshot snapshot() {
    return controlPlane.diagnostics().snapshot();
  }

  /**
   * Activates a plan.
   *
   * @param planJson the plan, in the startup-config JSON format
   * @return {@code activated} with the plan's handle id; otherwise {@code invalid} (malformed or
   *     failing validation), {@code conflict} (a scenario id is already active) or {@code rejected}
   *     (the runtime could not run it), with the reason
   */
  public ActivationResponse activate(final String planJson) {
    if (planJson == null || planJson.isBlank()) {
      return new ActivationResponse("invalid", null, "plan JSON must not be blank");
    }
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(planJson);
    } catch (final ConfigLoadException | IllegalArgumentException invalid) {
      LOGGER.log(Level.WARNING, invalid, () -> "chaos-agent: rejecting malformed plan JSON");
      return new ActivationResponse("invalid", null, "invalid plan: " + invalid.getMessage());
    }
    final ChaosActivationHandle handle;
    try {
      handle = controlPlane.activate(plan);
    } catch (final com.macstab.chaos.jvm.api.ChaosValidationException invalid) {
      return new ActivationResponse("invalid", null, "invalid plan: " + invalid.getMessage());
    } catch (final com.macstab.chaos.jvm.api.ChaosActivationException failed) {
      final boolean conflict =
          failed.getMessage() != null && failed.getMessage().contains("already active");
      return new ActivationResponse(
          conflict ? "conflict" : "invalid", null, "plan rejected: " + failed.getMessage());
    } catch (final RuntimeException rejected) {
      LOGGER.log(Level.WARNING, rejected, () -> "chaos-agent: plan activation rejected");
      return new ActivationResponse("rejected", null, "plan rejected: " + rejected.getMessage());
    }
    final ChaosActivationHandle previous = handles.put(handle.id(), handle);
    if (previous != null && !previous.equals(handle)) {
      previous.close();
    }
    return new ActivationResponse("activated", handle.id(), null);
  }

  /**
   * Stops a plan activated here.
   *
   * @param id the plan's handle id ({@code plan:<name>}) or its name
   * @return {@code stopped}, {@code unmanaged} (a scenario not activated here) or {@code not-found}
   */
  public StopResponse stop(final String id) {
    ChaosActivationHandle handle = handles.remove(id);
    if (handle == null) {
      handle = handles.remove(PLAN_PREFIX + id);
    }
    if (handle != null) {
      handle.close();
      return new StopResponse("stopped", id, null);
    }
    if (controlPlane.diagnostics().scenario(id).isPresent()) {
      return new StopResponse(
          "unmanaged", id, "not activated through this endpoint; stop it where it was activated");
    }
    return new StopResponse("not-found", id, "no plan or scenario with id " + id);
  }

  /**
   * Stops every plan activated here.
   *
   * @return how many plans were stopped
   */
  public StopAllResponse stopAll() {
    int stopped = 0;
    for (final String id : List.copyOf(handles.keySet())) {
      final ChaosActivationHandle handle = handles.remove(id);
      if (handle != null) {
        handle.close();
        stopped++;
      }
    }
    return new StopAllResponse(stopped);
  }

  /**
   * Serves one management HTTP request, for frameworks that route raw requests ({@code GET /}:
   * snapshot, {@code POST /} with the plan as the body: activate, {@code DELETE /}: stop all,
   * {@code DELETE /<id>}: stop).
   *
   * @param method the HTTP method
   * @param subPath the path below the endpoint's root: empty or {@code /<id>}, the id URL-encoded
   * @param body the request body; {@code null} without one
   * @return the status code and JSON body to answer with
   */
  public HttpResult handle(final String method, final String subPath, final String body) {
    final String path = subPath == null ? "" : subPath.replaceAll("^/+|/+$", "");
    switch (method == null ? "" : method.toUpperCase(java.util.Locale.ROOT)) {
      case "GET" -> {
        return path.isEmpty()
            ? new HttpResult(200, ChaosPlanMapper.toJson(snapshot()))
            : new HttpResult(404, error("unknown path"));
      }
      case "POST" -> {
        if (!path.isEmpty()) {
          return new HttpResult(404, error("unknown path"));
        }
        final ActivationResponse response = activate(body);
        return new HttpResult(statusOf(response.status()), ChaosPlanMapper.toJson(response));
      }
      case "DELETE" -> {
        if (path.isEmpty()) {
          return new HttpResult(200, ChaosPlanMapper.toJson(stopAll()));
        }
        final StopResponse response =
            stop(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8));
        return new HttpResult(statusOf(response.status()), ChaosPlanMapper.toJson(response));
      }
      default -> {
        return new HttpResult(405, error("use GET, POST or DELETE"));
      }
    }
  }

  /**
   * The HTTP status of an outcome: 200 done, 400 invalid, 404 not found, 409 conflict (an id
   * already active, or a scenario not activated here), 422 rejected by the runtime.
   */
  private static int statusOf(final String outcome) {
    return switch (outcome) {
      case "activated", "stopped" -> 200;
      case "invalid" -> 400;
      case "not-found" -> 404;
      case "conflict", "unmanaged" -> 409;
      default -> 422;
    };
  }

  private static String error(final String message) {
    return ChaosPlanMapper.toJson(Map.of("status", "error", "message", message));
  }

  /** Stops every plan activated here. */
  @Override
  public void close() {
    stopAll();
  }

  /**
   * The outcome of {@link #activate(String)}.
   *
   * @param status {@code activated}, {@code invalid}, {@code conflict} or {@code rejected}
   * @param handleId the plan's handle id when activated
   * @param message the reason when not
   */
  public record ActivationResponse(String status, String handleId, String message) {}

  /**
   * The outcome of {@link #stop(String)}.
   *
   * @param status {@code stopped}, {@code unmanaged} or {@code not-found}
   * @param id the id asked for
   * @param message details when not stopped
   */
  public record StopResponse(String status, String id, String message) {}

  /**
   * The outcome of {@link #stopAll()}.
   *
   * @param stoppedCount how many plans were stopped
   */
  public record StopAllResponse(int stoppedCount) {}

  /**
   * A response of {@link #handle(String, String, String)}.
   *
   * @param status the HTTP status code
   * @param json the JSON body
   */
  public record HttpResult(int status, String json) {}
}
