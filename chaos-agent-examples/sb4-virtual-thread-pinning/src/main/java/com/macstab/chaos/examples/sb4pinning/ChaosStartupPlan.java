package com.macstab.chaos.examples.sb4pinning;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class ChaosStartupPlan implements ApplicationListener<ApplicationReadyEvent> {

  private final ChaosControlPlane controlPlane;
  private final boolean contentionEnabled;
  private volatile ChaosActivationHandle handle;

  public ChaosStartupPlan(
      ChaosControlPlane controlPlane,
      @Value("${macstab.chaos.contention.enabled:false}") boolean contentionEnabled) {
    this.controlPlane = controlPlane;
    this.contentionEnabled = contentionEnabled;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (!contentionEnabled) {
      return;
    }
    ChaosScenario scenario =
        ChaosScenario.builder("metrics-lock-contention")
            .description(
                "Saturate the MetricsAggregator monitor with background contending threads")
            .selector(ChaosSelector.stress(ChaosSelector.StressTarget.MONITOR_CONTENTION))
            .effect(ChaosEffect.monitorContention(Duration.ofMillis(5), 8))
            .activationPolicy(ActivationPolicy.always())
            .build();
    handle = controlPlane.activate(scenario);
  }

  @PreDestroy
  public void shutdown() {
    ChaosActivationHandle h = handle;
    if (h != null) {
      h.stop();
    }
  }
}
