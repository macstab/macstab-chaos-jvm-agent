package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.core.AgentLog;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import com.macstab.chaos.jvm.startup.LogText;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.jspecify.annotations.Nullable;

/**
 * The operations of the chaos management endpoints, independent of any web framework: the Spring
 * actuator's {@code /actuator/chaos}, Micronaut's {@code /chaos} endpoint and Quarkus' {@code
 * /q/chaos} route offer the same four.
 *
 * <ul>
 *   <li>snapshot — the diagnostics: every scenario with its state and counters;
 *   <li>activate — activates a plan given as JSON; the plan's handle id is {@code plan:<name>}, and
 *       a plan whose name belongs to a running plan is a conflict (every plan without a name is
 *       named {@code default});
 *   <li>stop — stops a plan activated here, by its handle id or plan name;
 *   <li>stop all — stops every plan activated here.
 * </ul>
 *
 * <p>Only plans activated through this instance can be stopped through it; scenarios activated by
 * the application, a test or a startup plan are listed but left alone. Anyone who can reach an
 * endpoint can inject faults: the endpoints are off by default and must be secured.
 */
public final class ChaosManagement implements AutoCloseable {

  private static final AgentLog LOGGER = AgentLog.named(ChaosManagement.class.getName());
  private static final String PLAN_PREFIX = "plan:";

  private final ChaosControlPlane controlPlane;
  private final Map<String, ChaosActivationHandle> handles = new ConcurrentHashMap<>();

  /** Plan names an activation holds until it has stored its handle or failed. */
  private final java.util.Set<String> activating = ConcurrentHashMap.newKeySet();

  /** Set by {@link #close()}; a plan activated after it is stopped again at once. */
  private volatile boolean closed;

  /**
   * Bumped by {@link #stopAll()} before it looks at the handles: an activation that sees it changed
   * after storing its handle was missed by that stop-all and stops its plan itself.
   */
  private final AtomicLong stopAllGeneration = new AtomicLong();

  /** Runs between {@link #stop(String)}'s two lookups; a test seam, a no-op otherwise. */
  private final Runnable betweenStopLookups;

  /**
   * Creates the operations for {@code controlPlane}.
   *
   * @param controlPlane the control plane they act on
   */
  public ChaosManagement(final ChaosControlPlane controlPlane) {
    this(controlPlane, () -> {});
  }

  /**
   * Test seam: creates the operations with {@code betweenStopLookups} run between the two lookups
   * of {@link #stop(String)}, so a test can complete an activation exactly there.
   *
   * @param controlPlane the control plane they act on
   * @param betweenStopLookups run between the two lookups of a stop
   */
  ChaosManagement(final ChaosControlPlane controlPlane, final Runnable betweenStopLookups) {
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.betweenStopLookups = Objects.requireNonNull(betweenStopLookups, "betweenStopLookups");
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
   *     failing validation), {@code conflict} (a scenario id is already active, or a running plan
   *     has the plan's name) or {@code rejected} (the runtime could not run it, or a stop-all or
   *     close ran during the activation and stopped the plan), with the reason
   */
  public ActivationResponse activate(final @Nullable String planJson) {
    if (planJson == null || planJson.isBlank()) {
      return new ActivationResponse("invalid", null, "invalid plan: plan JSON must not be blank");
    }
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(planJson);
    } catch (final ConfigLoadException | IllegalArgumentException invalid) {
      // Only the sanitised message: the cause chain (Jackson's own message) quotes the input.
      LOGGER.log(
          Level.WARNING,
          () ->
              "[chaos-agent] rejecting malformed plan JSON: "
                  + LogText.sanitize(invalid.getMessage()));
      return new ActivationResponse("invalid", null, "invalid plan: " + invalid.getMessage());
    }
    // The name is claimed before activating, so a plan refused for its name never injects a fault.
    final String id = PLAN_PREFIX + plan.metadata().name();
    pruneFinished(id);
    if (!activating.add(id)) {
      return new ActivationResponse("conflict", null, inProgress(id));
    }
    try {
      return activateClaimed(plan, id);
    } finally {
      activating.remove(id);
    }
  }

  /** Activates {@code plan} while this call holds its name {@code id}. */
  private ActivationResponse activateClaimed(final ChaosPlan plan, final String id) {
    final ChaosActivationHandle running = handles.get(id);
    if (running != null && !finished(running)) {
      return nameConflict(id);
    }
    final long generation = stopAllGeneration.get();
    final ChaosActivationHandle handle;
    try {
      handle = controlPlane.activate(plan);
    } catch (final com.macstab.chaos.jvm.api.ChaosValidationException invalid) {
      return new ActivationResponse("invalid", null, "invalid plan: " + invalid.getMessage());
    } catch (final com.macstab.chaos.jvm.api.ChaosActivationConflictException conflict) {
      return new ActivationResponse("conflict", null, "plan conflict: " + conflict.getMessage());
    } catch (final com.macstab.chaos.jvm.api.ChaosInstrumentationException notWoven) {
      // The runtime's own failure, like the catch-all below: the detail goes to the log.
      LOGGER.log(Level.WARNING, notWoven, () -> "[chaos-agent] plan activation rejected");
      return new ActivationResponse(
          "rejected", null, "plan rejected: the runtime could not activate it (see the log)");
    } catch (final com.macstab.chaos.jvm.api.ChaosActivationException failed) {
      return new ActivationResponse("invalid", null, "invalid plan: " + failed.getMessage());
    } catch (final RuntimeException rejected) {
      // The runtime's own failure: the detail goes to the log, not to the caller.
      LOGGER.log(Level.WARNING, rejected, () -> "[chaos-agent] plan activation rejected");
      return new ActivationResponse(
          "rejected", null, "plan rejected: the runtime could not activate it (see the log)");
    }
    // A finished plan of the same name is replaced.
    final ChaosActivationHandle replaced = handles.put(handle.id(), handle);
    if (replaced != null && !replaced.equals(handle)) {
      replaced.close();
    }
    // A close() or stopAll() that ran meanwhile either found this handle and stopped it, or is seen
    // here; whichever removes the handle closes it.
    if (closed || stopAllGeneration.get() != generation) {
      if (handles.remove(handle.id(), handle)) {
        handle.close();
      }
      return new ActivationResponse(
          "rejected",
          null,
          closed
              ? "plan rejected: the endpoint is closing"
              : "plan rejected: stopped by a concurrent stop-all");
    }
    return new ActivationResponse("activated", handle.id(), null);
  }

  /**
   * Forgets the plans that finished on their own (spent or expired) and closes them, so uniquely
   * named, expiring plans do not pile up; as the MBean does. The plan named {@code activating} is
   * left to the activation, which replaces it; a handle removed meanwhile by a stop or a
   * replacement is left to whoever removed it.
   *
   * @param activating the handle id of the plan being activated
   */
  private void pruneFinished(final String activating) {
    for (final Map.Entry<String, ChaosActivationHandle> entry : List.copyOf(handles.entrySet())) {
      final ChaosActivationHandle handle = entry.getValue();
      if (!entry.getKey().equals(activating)
          && finished(handle)
          && handles.remove(entry.getKey(), handle)) {
        try {
          handle.close();
        } catch (final RuntimeException failure) {
          LOGGER.log(
              Level.WARNING,
              failure,
              () -> "[chaos-agent] could not close the finished plan " + entry.getKey());
        }
      }
    }
  }

  /** A plan that has finished (stopped, or expired) no longer holds its name. */
  static boolean finished(final ChaosActivationHandle handle) {
    final ChaosDiagnostics.ScenarioState state = handle.state();
    return state == ChaosDiagnostics.ScenarioState.STOPPED
        || state == ChaosDiagnostics.ScenarioState.INACTIVE;
  }

  private static String inProgress(final String id) {
    return "plan conflict: an activation of " + id + " is in progress; try again once it completed";
  }

  private static ActivationResponse nameConflict(final String id) {
    return new ActivationResponse(
        "conflict", null, "plan conflict: a running plan has its name (" + id + "); stop it first");
  }

  /**
   * Stops a plan activated here.
   *
   * @param id the plan's handle id ({@code plan:<name>}) or its name
   * @return {@code stopped}, {@code conflict} (the plan is being activated; it is not stopped),
   *     {@code unmanaged} (a scenario not activated here) or {@code not-found}
   */
  public StopResponse stop(final String id) {
    // The claim first: an activation stores its handle before it releases its claim, so a plan
    // whose claim is gone already has its handle stored. Looked up the other way round, a plan
    // stored between the two lookups would answer not-found and run.
    if (activating.contains(id) || activating.contains(PLAN_PREFIX + id)) {
      return new StopResponse("conflict", id, inProgress(id));
    }
    betweenStopLookups.run();
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
   * Stops every plan activated here, including a plan whose activation is in progress: that
   * activation stops its plan once stored and answers {@code rejected}.
   *
   * @return how many plans were stopped
   */
  public StopAllResponse stopAll() {
    stopAllGeneration.incrementAndGet();
    int stopped = 0;
    for (final String id : List.copyOf(handles.keySet())) {
      final ChaosActivationHandle handle = handles.remove(id);
      if (handle == null) {
        continue;
      }
      try {
        handle.close();
        stopped++;
      } catch (final RuntimeException failure) {
        // One plan that fails to stop must not keep the others running.
        LOGGER.log(Level.WARNING, failure, () -> "[chaos-agent] could not stop plan " + id);
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
  public HttpResult handle(final String method, final String subPath, final @Nullable String body) {
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
        return new HttpResult(httpStatus(response.status()), ChaosPlanMapper.toJson(response));
      }
      case "DELETE" -> {
        if (path.isEmpty()) {
          return new HttpResult(200, ChaosPlanMapper.toJson(stopAll()));
        }
        final String id;
        try {
          // A path is percent-decoded only: URLDecoder's form rule would turn a literal '+' into a
          // space (Quarkus passes the path as sent). A space arrives as %20.
          id =
              java.net.URLDecoder.decode(
                  path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException malformed) {
          return new HttpResult(400, error("malformed id"));
        }
        final StopResponse response = stop(id);
        return new HttpResult(httpStatus(response.status()), ChaosPlanMapper.toJson(response));
      }
      default -> {
        return new HttpResult(405, error("use GET, POST or DELETE"));
      }
    }
  }

  /**
   * The HTTP status of an outcome: 200 done, 400 invalid, 404 not found, 409 conflict (an id
   * already active, or a scenario not activated here), 422 rejected by the runtime.
   *
   * @param outcome the {@code status} of a response
   * @return the HTTP status to answer with
   */
  public static int httpStatus(final String outcome) {
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

  /**
   * Stops every plan activated here; a plan whose activation completes afterwards is stopped too.
   */
  @Override
  public void close() {
    closed = true;
    stopAll();
  }

  /**
   * The outcome of {@link #activate(String)}.
   *
   * @param status {@code activated}, {@code invalid}, {@code conflict} or {@code rejected}
   * @param handleId the plan's handle id when activated
   * @param message the reason when not
   */
  public record ActivationResponse(
      String status, @Nullable String handleId, @Nullable String message) {}

  /**
   * The outcome of {@link #stop(String)}.
   *
   * @param status {@code stopped}, {@code conflict} (the plan is being activated; it is not
   *     stopped), {@code unmanaged} (a scenario not activated here) or {@code not-found}
   * @param scenarioId the id asked for: a plan's handle id ({@code plan:<name>}) or its name
   * @param message details when not stopped
   */
  public record StopResponse(String status, String scenarioId, @Nullable String message) {}

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
