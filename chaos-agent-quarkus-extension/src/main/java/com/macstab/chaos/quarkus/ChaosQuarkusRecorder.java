package com.macstab.chaos.quarkus;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Quarkus build-time recorder that installs the chaos agent at runtime initialization.
 *
 * <p>Recorders are invoked by Quarkus' build-time processor during image build. The generated
 * bytecode calls {@link #installAgent()} from the generated {@code runtime-init} hook, which
 * triggers a self-attach via {@link ChaosPlatform#installLocally()}.
 *
 * <p>The returned {@link RuntimeValue} is a boolean marker — its presence in the generated graph
 * guarantees the agent install call occurs exactly once per application startup. Consumers that
 * need the live {@link com.macstab.chaos.api.ChaosControlPlane} should obtain it via the
 * CDI-managed {@link ChaosArcProducer}.
 */
@Recorder
public class ChaosQuarkusRecorder {

  /** Default constructor invoked by the Quarkus build step. */
  public ChaosQuarkusRecorder() {}

  /**
   * Installs the chaos agent in the current JVM. Idempotent; subsequent calls return {@code true}
   * without re-attaching.
   *
   * @return a {@link RuntimeValue} holding {@code true} after the agent is installed
   */
  public RuntimeValue<Boolean> installAgent() {
    ChaosPlatform.installLocally();
    return new RuntimeValue<>(Boolean.TRUE);
  }
}
