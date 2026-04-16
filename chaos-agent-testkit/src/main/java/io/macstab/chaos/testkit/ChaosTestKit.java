package io.macstab.chaos.testkit;

import io.macstab.chaos.api.ChaosControlPlane;
import io.macstab.chaos.api.ChaosSession;
import io.macstab.chaos.bootstrap.ChaosPlatform;

public final class ChaosTestKit {
  private ChaosTestKit() {}

  public static ChaosControlPlane install() {
    return ChaosPlatform.installLocally();
  }

  public static ChaosSession openSession(final String displayName) {
    return install().openSession(displayName);
  }
}
