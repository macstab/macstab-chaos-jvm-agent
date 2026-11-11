package com.macstab.chaos.jvm.examples.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.testkit.WithChaos;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A test class that inherits {@code @QuarkusChaosTest} from its base class is a test-class bean as
 * well: its own {@code @Inject} field and the base's are injected.
 */
@DisplayName("a subclass of a @QuarkusChaosTest base class")
class InheritedQuarkusChaosTestTest extends AbstractChaosAppTest {

  @Inject NapService naps;

  @Test
  @WithChaos(
      """
      {"id": "qk-app-inherited-sleep", "scope": "SESSION",
       "selector": {"type": "thread", "operations": ["THREAD_SLEEP"]},
       "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
      """)
  @DisplayName("injects its own field and the base class's tracking plane")
  void injectsTheSubclassAndTheBaseFields() throws Exception {
    assertThat(ClientProxy.unwrap(controlPlane).getClass().getName())
        .isEqualTo("com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane");

    assertThat(naps.nap()).isGreaterThanOrEqualTo(180);
    assertThat(
            controlPlane
                .diagnostics()
                .scenario("qk-app-inherited-sleep")
                .orElseThrow()
                .appliedCount())
        .isPositive();
  }
}
