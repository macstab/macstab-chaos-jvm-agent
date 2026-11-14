package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Activates the {@link WithChaos} scenarios declared on a test class or method. Shared by every
 * test extension, so the annotation is read the same way in plain JUnit, Spring, Micronaut and
 * Quarkus tests; which threads a session-scoped scenario reaches depends on the extension (see
 * {@link WithChaos}).
 */
public final class WithChaosSupport {

  private WithChaosSupport() {}

  /**
   * Activates the plan of every {@link WithChaos} on {@code element}: its session-scoped scenarios
   * as one plan in {@code session}, its JVM-scoped ones as one plan on {@code controlPlane}. Both
   * keep the plan's metadata and observability settings.
   *
   * @param element the test class or method
   * @param controlPlane the control plane for JVM-scoped scenarios
   * @param session the test's session for session-scoped scenarios
   * @return the handles of the activated plans, for the caller to close when the test ends
   * @throws IllegalArgumentException if an annotation names neither or both JSON sources, the
   *     resource does not exist, or the JSON is invalid (with its line and column); the handles
   *     already activated are closed first, and a failure to close one is suppressed in it
   */
  public static List<ChaosActivationHandle> activate(
      final AnnotatedElement element,
      final ChaosControlPlane controlPlane,
      final ChaosSession session) {
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      for (final WithChaos annotation :
          AnnotationSupport.findRepeatableAnnotations(element, WithChaos.class)) {
        final ChaosPlan plan = plan(annotation, element);
        final ChaosPlan jvm = scoped(plan, ChaosScenario.ScenarioScope.JVM);
        final ChaosPlan inSession = scoped(plan, ChaosScenario.ScenarioScope.SESSION);
        if (jvm != null) {
          handles.add(controlPlane.activate(jvm));
        }
        if (inSession != null) {
          handles.add(session.activate(inSession));
        }
      }
    } catch (final RuntimeException failure) {
      // The activation failure is what the test reports; a failing rollback must not replace it.
      try {
        close(handles);
      } catch (final RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
    return handles;
  }

  /**
   * Closes handles returned by {@link #activate}, continuing past failures.
   *
   * @param handles the handles; may be {@code null}
   * @throws RuntimeException the first failure to close a handle, with the later ones suppressed in
   *     it
   */
  public static void close(final @Nullable List<ChaosActivationHandle> handles) {
    if (handles == null) {
      return;
    }
    RuntimeException first = null;
    for (final ChaosActivationHandle handle : handles) {
      try {
        handle.close();
      } catch (final RuntimeException failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    handles.clear();
    if (first != null) {
      throw first;
    }
  }

  /**
   * The part of {@code plan} with scenarios of {@code scope}, with the plan's metadata and
   * observability; the plan itself when all its scenarios have that scope, {@code null} when none
   * has.
   */
  private static @Nullable ChaosPlan scoped(
      final ChaosPlan plan, final ChaosScenario.ScenarioScope scope) {
    final List<ChaosScenario> scenarios =
        plan.scenarios().stream().filter(scenario -> scenario.scope() == scope).toList();
    if (scenarios.isEmpty()) {
      return null;
    }
    return scenarios.size() == plan.scenarios().size()
        ? plan
        : new ChaosPlan(plan.metadata(), plan.observability(), scenarios);
  }

  private static ChaosPlan plan(final WithChaos annotation, final AnnotatedElement element) {
    final boolean inline = !annotation.value().isBlank();
    final boolean fromResource = !annotation.resource().isBlank();
    if (inline == fromResource) {
      throw new IllegalArgumentException(
          "@WithChaos on " + element + " needs exactly one of value (JSON) and resource");
    }
    final String json = inline ? annotation.value() : readResource(annotation.resource(), element);
    try {
      // By structure, not substring: a scenario whose id mentioned "scenarios" was read as a plan.
      return ChaosPlanMapper.readPlanOrScenarios(json, "@WithChaos");
    } catch (final ConfigLoadException invalid) {
      // The message names the line and column of a parse failure.
      throw new IllegalArgumentException(invalid.getMessage(), invalid);
    }
  }

  private static String readResource(final String resource, final AnnotatedElement element) {
    String path = resource.strip();
    if (path.startsWith("classpath:")) {
      path = path.substring("classpath:".length());
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    final ClassLoader loader =
        Thread.currentThread().getContextClassLoader() != null
            ? Thread.currentThread().getContextClassLoader()
            : WithChaosSupport.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "@WithChaos resource not found on the class path: " + resource + " (" + element + ")");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException unreadable) {
      throw new IllegalArgumentException("cannot read @WithChaos resource " + resource, unreadable);
    }
  }
}
