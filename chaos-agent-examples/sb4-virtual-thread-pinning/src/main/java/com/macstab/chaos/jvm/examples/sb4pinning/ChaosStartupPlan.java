package com.macstab.chaos.jvm.examples.sb4pinning;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Spring application listener that activates a chaos plan on startup. */
@Component
public class ChaosStartupPlan implements ApplicationListener<ApplicationReadyEvent> {

  private final ChaosControlPlane controlPlane;
  private final boolean contentionEnabled;
  private volatile ChaosActivationHandle handle;

  /**
   * Creates a new ChaosStartupPlan.
   *
   * @param controlPlane the chaos control plane used to activate scenarios
   * @param contentionEnabled whether virtual-thread carrier pinning chaos is enabled
   */
  public ChaosStartupPlan(
      final ChaosControlPlane controlPlane,
      @Value("${macstab.chaos.contention.enabled:false}") final boolean contentionEnabled) {
    this.controlPlane = controlPlane;
    this.contentionEnabled = contentionEnabled;
  }

  @Override
  public void onApplicationEvent(final ApplicationReadyEvent event) {
    if (!contentionEnabled) {
      return;
    }
    // The previous scenario used MonitorContentionEffect on its own private
    // ReentrantLock, which never touches MetricsAggregator's monitor or any
    // application code path — useful for stressing the contention machinery
    // itself, useless for demonstrating carrier pinning.
    //
    // VirtualThreadCarrierPinningEffect pins the virtual-thread carrier threads
    // in the ForkJoin pool for a bounded duration, exactly reproducing the
    // symptom the demo teaches: virtual threads making progress slowly because
    // their carriers are held up in synchronized / native code elsewhere.
    final ChaosScenario scenario =
        ChaosScenario.builder("virtual-thread-carrier-pinning")
            .description(
                "Pin virtual-thread carriers for short bursts so concurrent POST /metrics"
                    + " requests reveal the observability of pinning in jfr/JDK Flight Recorder.")
            .selector(
                ChaosSelector.stress(ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
            .effect(ChaosEffect.virtualThreadCarrierPinning(4, Duration.ofMillis(25)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handle = controlPlane.activate(scenario);
  }

  /** Stops the active chaos handle. */
  @PreDestroy
  public void shutdown() {
    final ChaosActivationHandle h = handle;
    if (h != null) {
      h.stop();
    }
  }
}
