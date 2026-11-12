package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exception classes an exception injection cannot use, refused or reported (ADR 0005). */
@DisplayName("Injected exception classes at their edges")
class InjectedExceptionEdgesTest {

  /** A Throwable that is not public: nothing outside this class may construct it. */
  @SuppressWarnings("unused")
  private static final class Hidden extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Hidden(final String message) {
      super(message);
    }
  }

  /** A public Throwable whose (String) constructor fails. */
  public static final class Exploding extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Exploding(final String message) {
      super(message);
      throw new IllegalStateException("constructor failed: " + message);
    }
  }

  @Test
  @DisplayName("a public abstract Throwable (VirtualMachineError) is refused at activation")
  void abstractClassIsRefused() {
    assertThatThrownBy(() -> FailureFactory.injectedConstructor("java.lang.VirtualMachineError"))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessage(
            "exception class must be public and not abstract: java.lang.VirtualMachineError");
  }

  @Test
  @DisplayName("a Throwable that is not public is refused at activation")
  void hiddenClassIsRefused() {
    assertThatThrownBy(() -> FailureFactory.injectedConstructor(Hidden.class.getName()))
        .isInstanceOf(ChaosValidationException.class)
        .hasMessageStartingWith("exception class must be public and not abstract");
  }

  @Test
  @DisplayName(
      "a constructor that fails when the scenario fires gives a RuntimeException naming the class")
  void failingConstructorIsReported() throws Exception {
    final ChaosEffect.ExceptionInjectionEffect effect =
        new ChaosEffect.ExceptionInjectionEffect("java.lang.IllegalStateException", "boom", true);

    final Throwable injected =
        FailureFactory.injected(effect, Exploding.class.getConstructor(String.class));

    assertThat(injected)
        .isExactlyInstanceOf(RuntimeException.class)
        .hasMessage("[chaos-agent] failed to instantiate java.lang.IllegalStateException")
        .hasCauseInstanceOf(InvocationTargetException.class);
    assertThat(injected.getCause().getCause()).hasMessage("constructor failed: boom");
  }
}
