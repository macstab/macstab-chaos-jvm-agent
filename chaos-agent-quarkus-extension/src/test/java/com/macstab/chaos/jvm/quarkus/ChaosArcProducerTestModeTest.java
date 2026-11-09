package com.macstab.chaos.jvm.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import io.quarkus.runtime.LaunchMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * In a Quarkus test the control-plane bean is produced in Quarkus's class loader, while {@link
 * ChaosQuarkusExtension} runs in the system class loader that JUnit loaded it from. The producer is
 * loaded here into a child-first loader that, like Quarkus's, loads the extension and the testkit
 * itself and leaves the api and the agent (parent-first artifacts) to the system loader; the
 * extension of this test is the system loader's. Without a Quarkus build no {@code @QuarkusTest}
 * runs here: the loader split is the part that decides whether the bean is tracked.
 */
@DisplayName("ChaosArcProducer in a Quarkus test")
class ChaosArcProducerTestModeTest {

  private LaunchMode previous;

  @BeforeEach
  void testMode() {
    assumeThat(ChaosQuarkusExtension.class.getClassLoader())
        .as("the test classes are on the system class path")
        .isSameAs(ClassLoader.getSystemClassLoader());
    previous = LaunchMode.current();
    LaunchMode.set(LaunchMode.TEST);
    ChaosPlatform.installLocally();
  }

  @AfterEach
  void restoreMode() {
    if (previous != null) {
      LaunchMode.set(previous);
    }
  }

  @Test
  @DisplayName("the injected control plane is the one the system-loader extension tracks")
  void injectedPlaneIsTracked() throws Exception {
    try (URLClassLoader quarkusLoader = quarkusLikeLoader()) {
      final ChaosControlPlane plane = produce(quarkusLoader);

      assertThat(plane.getClass().getName()).isEqualTo(TrackingChaosControlPlane.class.getName());
      assertThat(contextPlanes()).contains(plane);
    }
  }

  @Test
  @DisplayName(
      "a scenario activated through the injected plane ends with the last chaos class; its"
          + " listeners stay")
  void injectedActivationIsReleasedAfterTheClass() throws Exception {
    final ChaosQuarkusExtension extension = new ChaosQuarkusExtension();
    final ExtensionContext classContext = classContext();
    try (URLClassLoader quarkusLoader = quarkusLikeLoader()) {
      extension.beforeAll(classContext);
      final ChaosControlPlane plane = produce(quarkusLoader);
      final com.macstab.chaos.jvm.api.ChaosEventListener listener = event -> {};
      plane.addEventListener(listener);
      plane.activate(scenario("quarkus-injected"));
      assertThat(ChaosPlatform.current().diagnostics().scenario("quarkus-injected")).isPresent();

      extension.afterAll(classContext);

      assertThat(ChaosPlatform.current().diagnostics().scenario("quarkus-injected")).isEmpty();
      assertThat(plane.removeEventListener(listener))
          .as("the application's listener is the application's")
          .isTrue();
    }
  }

  @Test
  @DisplayName("disposing the bean stops what it activated and forgets the plane")
  void disposingReleasesThePlane() throws Exception {
    try (URLClassLoader quarkusLoader = quarkusLikeLoader()) {
      final Class<?> producerClass = quarkusLoader.loadClass(ChaosArcProducer.class.getName());
      final Object producer = producerClass.getConstructor().newInstance();
      final ChaosControlPlane plane =
          (ChaosControlPlane) producerClass.getMethod("chaosControlPlane").invoke(producer);
      plane.activate(scenario("quarkus-disposed"));

      final Method release = producerClass.getDeclaredMethod("release", ChaosControlPlane.class);
      release.setAccessible(true);
      release.invoke(producer, plane);

      assertThat(ChaosPlatform.current().diagnostics().scenario("quarkus-disposed")).isEmpty();
      assertThat(contextPlanes()).doesNotContain(plane);
    }
  }

  @Test
  @DisplayName("outside a test the producer hands out the JVM's control plane itself")
  void normalModeIsNotTracked() throws Exception {
    LaunchMode.set(LaunchMode.NORMAL);
    try (URLClassLoader quarkusLoader = quarkusLikeLoader()) {
      assertThat(produce(quarkusLoader)).isSameAs(ChaosPlatform.current());
    }
  }

  @Test
  @DisplayName("outside a test disposing the bean stops nothing")
  void normalModeDisposalKeepsScenarios() throws Exception {
    LaunchMode.set(LaunchMode.NORMAL);
    final ChaosArcProducer producer = new ChaosArcProducer();
    final ChaosControlPlane plane = producer.chaosControlPlane();
    try (var handle = plane.activate(scenario("quarkus-normal-disposed"))) {
      producer.release(plane);

      assertThat(ChaosPlatform.current().diagnostics().scenario("quarkus-normal-disposed"))
          .isPresent();
    }
  }

  @Test
  @DisplayName("releasing a plane the extension did not hand out stops nothing")
  void releasingAForeignPlaneStopsNothing() {
    final TrackingChaosControlPlane foreign =
        new TrackingChaosControlPlane(ChaosPlatform.current());
    try (var handle = foreign.activate(scenario("quarkus-foreign"))) {
      ChaosQuarkusExtension.releaseContextPlane(foreign);
      ChaosQuarkusExtension.releaseContextPlane(ChaosPlatform.current());

      assertThat(ChaosPlatform.current().diagnostics().scenario("quarkus-foreign")).isPresent();
    }
  }

  private static ChaosControlPlane produce(final ClassLoader quarkusLoader) throws Exception {
    final Class<?> producerClass = quarkusLoader.loadClass(ChaosArcProducer.class.getName());
    assertThat(producerClass).isNotSameAs(ChaosArcProducer.class);
    return (ChaosControlPlane)
        producerClass
            .getMethod("chaosControlPlane")
            .invoke(producerClass.getConstructor().newInstance());
  }

  /** The system loader's {@code ChaosQuarkusExtension.CONTEXT_PLANES}. */
  private static Set<Object> contextPlanes() throws ReflectiveOperationException {
    final Field planes = ChaosQuarkusExtension.class.getDeclaredField("CONTEXT_PLANES");
    planes.setAccessible(true);
    @SuppressWarnings("unchecked")
    final Set<Object> set = (Set<Object>) planes.get(null);
    return set;
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .build();
  }

  /**
   * A loader that loads the Quarkus extension and the testkit itself and delegates everything else
   * (the api, the agent, Quarkus, JUnit) to the system loader, as Quarkus's test class loader does
   * with the parent-first artifacts of {@code quarkus-extension.properties}.
   */
  private static URLClassLoader quarkusLikeLoader() {
    final URL[] urls = {
      ChaosArcProducer.class.getProtectionDomain().getCodeSource().getLocation(),
      TrackingChaosControlPlane.class.getProtectionDomain().getCodeSource().getLocation()
    };
    return new URLClassLoader("quarkus-like", urls, ClassLoader.getSystemClassLoader()) {
      @Override
      protected Class<?> loadClass(final String name, final boolean resolve)
          throws ClassNotFoundException {
        if (!name.startsWith("com.macstab.chaos.jvm.quarkus.")
            && !name.startsWith("com.macstab.chaos.jvm.testkit.")) {
          return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded == null) {
            loaded = findClass(name);
          }
          if (resolve) {
            resolveClass(loaded);
          }
          return loaded;
        }
      }
    };
  }

  /** The class context JUnit hands the extension for a test class without annotations. */
  private static ExtensionContext classContext() {
    final Map<Object, Object> values = new HashMap<>();
    final ExtensionContext.Store store =
        (ExtensionContext.Store)
            Proxy.newProxyInstance(
                ExtensionContext.Store.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.Store.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "get" -> values.get(args[0]);
                      case "put" -> values.put(args[0], args[1]);
                      case "remove" -> values.remove(args[0]);
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return (ExtensionContext)
        Proxy.newProxyInstance(
            ExtensionContext.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getStore" -> store;
                  case "getParent" -> Optional.empty();
                  case "getTestClass" -> Optional.of(Fixture.class);
                  case "getRequiredTestClass" -> Fixture.class;
                  case "getDisplayName" -> "Fixture";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  /** A chaos test class without annotations. */
  static final class Fixture {}
}
