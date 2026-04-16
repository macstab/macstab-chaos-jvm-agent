package com.macstab.chaos.testkit;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;

public final class ChaosTestKit {
  private ChaosTestKit() {}

  public static ChaosControlPlane install() {
    return ChaosPlatform.installLocally();
  }

  public static ChaosSession openSession(final String displayName) {
    return install().openSession(displayName);
  }
}
