package com.macstab.chaos.jvm.quarkus;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

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
 * com.macstab.chaos.jvm.api.ChaosControlPlane} should obtain it via the CDI-managed {@link
 * ChaosArcProducer}.
 */
@Recorder
public class ChaosQuarkusRecorder {

  /**
   * Property read through MicroProfile Config at runtime-init time to decide whether to install the
   * chaos agent: a system property, environment variable or {@code application.properties} entry.
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
    } catch (final RuntimeException | LinkageError exception) {
      // LinkageError: self-attach is unavailable in a native image and on JVMs without the
      // attach mechanism; that must not abort application startup.
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Quarkus runtime-init");
      return new RuntimeValue<>(Boolean.FALSE);
    }
  }

  /**
   * Reads {@code macstab.chaos.enabled} at run time through MicroProfile Config: system properties,
   * environment variables ({@code MACSTAB_CHAOS_ENABLED}) and {@code application.properties}. The
   * runtime-init attach and the {@link ChaosArcProducer} both decide with it.
   */
  static boolean chaosEnabled() {
    return ConfigProvider.getConfig()
        .getOptionalValue(ENABLED_PROPERTY, Boolean.class)
        .orElse(Boolean.FALSE);
  }
}
