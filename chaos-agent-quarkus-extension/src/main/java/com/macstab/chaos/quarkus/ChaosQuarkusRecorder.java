package com.macstab.chaos.quarkus;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Quarkus build-time recorder that installs the chaos agent at runtime initialization.
 *
 * <p>Recorders are invoked by Quarkus' build-time processor during image build. The generated
 * bytecode calls {@link #installAgent()} from the generated {@code runtime-init} hook, which
 * triggers a self-attach via {@link ChaosPlatform#installLocally()} — but only when {@link
 * #ENABLED_PROPERTY} is set to {@code true} on the launched JVM. When the flag is absent or {@code
 * false} the recorder is a no-op, so the extension jar can ship with production artefacts without
 * triggering byte-code instrumentation in every environment. The same property gates the Spring
 * Boot starters and the Micronaut configurer so one configuration switch controls all supported
 * stacks.
 *
 * <p>The returned {@link RuntimeValue} is a boolean marker — its value reflects whether the install
 * call was actually executed (i.e. the gate was open). Consumers that need the live {@link
 * com.macstab.chaos.api.ChaosControlPlane} should obtain it via the CDI-managed {@link
 * ChaosArcProducer}.
 */
@Recorder
public class ChaosQuarkusRecorder {

  /**
   * System property / environment variable consulted at runtime-init time to decide whether to
   * install the chaos agent. Matches the Spring Boot starter and Micronaut configurer gates.
   */
  public static final String ENABLED_PROPERTY = "macstab.chaos.enabled";

  private static final Logger LOGGER = Logger.getLogger(ChaosQuarkusRecorder.class.getName());

  /** Default constructor invoked by the Quarkus build step. */
  public ChaosQuarkusRecorder() {}

  /**
   * Installs the chaos agent in the current JVM if {@link #ENABLED_PROPERTY} resolves to {@code
   * true}; otherwise returns immediately without touching the chaos platform. Idempotent when
   * enabled: {@link ChaosPlatform#installLocally()} tolerates repeated invocations on a JVM that
   * already has the agent installed.
   *
   * @return a {@link RuntimeValue} holding {@code true} when installation succeeded and the gate
   *     was open; {@code false} when the gate was closed or installation threw. Returning a value
   *     instead of throwing keeps Quarkus startup resilient when the chaos platform is unavailable
   *     — production traffic must not fail to boot because of an optional observability add-on.
   */
  public RuntimeValue<Boolean> installAgent() {
    if (!chaosEnabled()) {
      return new RuntimeValue<>(Boolean.FALSE);
    }
    try {
      ChaosPlatform.installLocally();
      return new RuntimeValue<>(Boolean.TRUE);
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Quarkus runtime-init");
      return new RuntimeValue<>(Boolean.FALSE);
    }
  }

  /**
   * Resolves the {@value #ENABLED_PROPERTY} flag, preferring the system property over the
   * environment variable. The environment fallback uses upper-snake-case ({@code
   * MACSTAB_CHAOS_ENABLED}) to match twelve-factor conventions used by Quarkus itself.
   */
  private static boolean chaosEnabled() {
    final String sysProp = System.getProperty(ENABLED_PROPERTY);
    if (sysProp != null) {
      return Boolean.parseBoolean(sysProp);
    }
    final String envVar = System.getenv(ENABLED_PROPERTY.replace('.', '_').toUpperCase());
    return Boolean.parseBoolean(envVar);
  }
}
