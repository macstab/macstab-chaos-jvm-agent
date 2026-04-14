package io.macstab.chaos.bootstrap;

import io.macstab.chaos.api.ChaosControlPlane;

public final class ChaosPlatform {
  private ChaosPlatform() {}

  public static ChaosControlPlane installLocally() {
    return ChaosAgentBootstrap.installForLocalTests();
  }

  public static ChaosControlPlane current() {
    return ChaosAgentBootstrap.current();
  }
}
