package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;

/**
 * Probe main for the JVM-wide control plane of {@link ChaosPlatform#installLocally()}: closes it
 * the natural way (try-with-resources) and prints {@code close} (what the close did), then whether
 * a later {@code installLocally()} still activates ({@code activate}) and opens a session ({@code
 * session}).
 */
public final class SharedPlaneCloseProbeMain {

  private SharedPlaneCloseProbeMain() {}

  public static void main(final String[] args) {
    String close;
    try (ChaosControlPlane plane = ChaosPlatform.installLocally()) {
      close = "returned:" + plane.getClass().getSimpleName();
    } catch (final UnsupportedOperationException refused) {
      close = "refused:" + refused.getMessage();
    }
    System.out.println("close=" + close);
    try (ChaosActivationHandle handle =
        ChaosPlatform.installLocally()
            .activate(
                ChaosScenario.builder("after-close")
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      System.out.println("activate=" + handle.state());
    } catch (final RuntimeException failure) {
      System.out.println("activate=" + failure);
    }
    try (ChaosSession session = ChaosPlatform.current().openSession("after-close")) {
      System.out.println("session=open");
    } catch (final RuntimeException failure) {
      System.out.println("session=" + failure);
    }
    System.out.println("done=true");
  }
}
