package com.macstab.chaos.jvm.examples.quarkus;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.quarkus.QuarkusChaosTest;
import jakarta.inject.Inject;

/**
 * A base class that carries {@code @QuarkusChaosTest} for its subclasses: the annotation is
 * inherited, so a subclass is a chaos test with the base's injected fields and its own.
 */
@QuarkusChaosTest
abstract class AbstractChaosAppTest {

  @Inject ChaosControlPlane controlPlane;
}
