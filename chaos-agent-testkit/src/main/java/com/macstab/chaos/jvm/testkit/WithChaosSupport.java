package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
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
   * Activates the scenarios of every {@link WithChaos} on {@code element}: session-scoped ones in
   * {@code session}, JVM-scoped ones on {@code controlPlane}.
   *
   * @param element the test class or method
   * @param controlPlane the control plane for JVM-scoped scenarios
   * @param session the test's session for session-scoped scenarios
   * @return the handles of the activated scenarios, for the caller to close when the test ends
   * @throws IllegalArgumentException if an annotation names neither or both JSON sources, the
   *     resource does not exist, or the JSON is invalid
   */
  public static List<ChaosActivationHandle> activate(
      final AnnotatedElement element,
      final ChaosControlPlane controlPlane,
      final ChaosSession session) {
    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      for (final WithChaos annotation :
          AnnotationSupport.findRepeatableAnnotations(element, WithChaos.class)) {
        for (final ChaosScenario scenario : plan(annotation, element).scenarios()) {
          handles.add(
              scenario.scope() == ChaosScenario.ScenarioScope.SESSION
                  ? session.activate(scenario)
                  : controlPlane.activate(scenario));
        }
      }
    } catch (final RuntimeException failure) {
      close(handles);
      throw failure;
    }
    return handles;
  }

  /**
   * Closes handles returned by {@link #activate}, continuing past failures.
   *
   * @param handles the handles; may be {@code null}
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
        }
      }
    }
    handles.clear();
    if (first != null) {
      throw first;
    }
  }

  private static ChaosPlan plan(final WithChaos annotation, final AnnotatedElement element) {
    final boolean inline = !annotation.value().isBlank();
    final boolean fromResource = !annotation.resource().isBlank();
    if (inline == fromResource) {
      throw new IllegalArgumentException(
          "@WithChaos on " + element + " needs exactly one of value (JSON) and resource");
    }
    final String json = inline ? annotation.value() : readResource(annotation.resource(), element);
    // By structure, not substring: a scenario whose id mentioned "scenarios" was read as a plan.
    return ChaosPlanMapper.readPlanOrScenarios(json, "@WithChaos");
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
