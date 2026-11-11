package com.macstab.chaos.jvm.quarkus.deployment;

import com.macstab.chaos.jvm.quarkus.ChaosArcProducer;
import com.macstab.chaos.jvm.quarkus.ChaosQuarkusRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.TestClassBeanBuildItem;
import java.lang.reflect.Modifier;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

/**
 * Build-time processor that registers the chaos-agent extension with the Quarkus build graph.
 *
 * <p>This class lives in the deployment artifact — a separate jar from the runtime artifact — so
 * that {@code io.quarkus.deployment.*} types never leak onto the application's runtime classpath.
 * Quarkus discovers the class via the {@code deployment-artifact:} pointer declared in the runtime
 * module's {@code META-INF/quarkus-extension.yaml}.
 *
 * <p>The work is intentionally split across two {@link BuildStep} methods:
 *
 * <ul>
 *   <li>{@link #feature()} — a non-recording step that produces the {@link FeatureBuildItem} so the
 *       feature appears in the Quarkus startup banner. Must NOT be {@link Record @Record} — Quarkus
 *       forbids returning a build item other than {@code void} or a {@code
 *       BytecodeRecorderObjectLoader} from a recording step.
 *   <li>{@link #installChaosAgent(ChaosQuarkusRecorder)} — a {@link Record recording} step whose
 *       sole responsibility is to emit the runtime-init call into the recorder bytecode. It returns
 *       {@code void} and performs no build-item production, satisfying the recording contract.
 * </ul>
 *
 * <p>The two are separate methods because one method returning {@link FeatureBuildItem} while
 * annotated {@code @Record(RUNTIME_INIT)} fails the build: the deployment engine would run it both
 * for recording and for the build-item output.
 */
public final class ChaosQuarkusBuildStep {

  /** Feature name reported in the Quarkus startup banner. */
  private static final String FEATURE_NAME = "macstab-chaos-agent";

  /** {@code com.macstab.chaos.jvm.quarkus.QuarkusChaosTest}, by name: it is test support only. */
  private static final DotName QUARKUS_CHAOS_TEST =
      DotName.createSimple("com.macstab.chaos.jvm.quarkus.QuarkusChaosTest");

  /** Default constructor; Quarkus instantiates build-step classes reflectively. */
  public ChaosQuarkusBuildStep() {}

  /**
   * Produces the feature marker for the Quarkus startup banner.
   *
   * @return a {@link FeatureBuildItem} whose name is {@link #FEATURE_NAME}
   */
  @BuildStep
  public FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE_NAME);
  }

  /**
   * Registers {@link ChaosArcProducer} as a bean. The runtime jar carries no Jandex index and no
   * {@code beans.xml}, so ArC would not discover the producer on its own. Unremovable because the
   * bean is typically injected only into test classes, which ArC does not see as injection points.
   *
   * @return the additional-bean registration
   */
  @BuildStep
  public AdditionalBeanBuildItem producerBean() {
    return AdditionalBeanBuildItem.unremovableOf(ChaosArcProducer.class);
  }

  /**
   * Makes every test class annotated with {@code @QuarkusChaosTest} a bean, and every concrete
   * subclass of one (the annotation is inherited), as Quarkus does for a {@code @QuarkusTest}
   * class, so its {@code @Inject} fields are injected. Quarkus finds a test class only by an
   * {@code @ExtendWith(QuarkusTestExtension.class)} it sees in the indexed test classes; the one on
   * {@code @QuarkusChaosTest} is in this extension's jar, which it does not index, so without this
   * step a {@code @QuarkusChaosTest} class with an injected field cannot be instantiated.
   *
   * @param index the application's combined index, which holds the test classes in test mode
   * @param testClassBeans where the test classes are registered
   */
  @BuildStep
  public void chaosTestClassBeans(
      final CombinedIndexBuildItem index,
      final BuildProducer<TestClassBeanBuildItem> testClassBeans) {
    for (final AnnotationInstance annotation :
        index.getIndex().getAnnotations(QUARKUS_CHAOS_TEST)) {
      if (annotation.target().kind() == AnnotationTarget.Kind.CLASS
          && !annotation.target().asClass().isAnnotation()) {
        final ClassInfo annotated = annotation.target().asClass();
        testClassBeans.produce(new TestClassBeanBuildItem(annotated.name().toString()));
        // The annotation is @Inherited: JUnit runs a subclass as a chaos test too, and Jandex
        // lists only the class that declares it.
        for (final ClassInfo subclass : index.getIndex().getAllKnownSubclasses(annotated.name())) {
          if (!Modifier.isAbstract(subclass.flags())) {
            testClassBeans.produce(new TestClassBeanBuildItem(subclass.name().toString()));
          }
        }
      }
    }
  }

  /**
   * Records the runtime-init call that installs the chaos agent before any application code runs.
   *
   * <p>Void return and {@code @Record(RUNTIME_INIT)}: the build step is a pure recorder with no
   * build-item output. The generated bytecode calls {@link ChaosQuarkusRecorder#installAgent()} on
   * the live recorder at application startup.
   *
   * @param recorder the build-time recorder proxy; method calls on it are recorded as bytecode
   */
  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  public void installChaosAgent(final ChaosQuarkusRecorder recorder) {
    recorder.installAgent();
  }
}
