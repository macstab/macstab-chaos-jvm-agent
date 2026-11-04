package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of method entry and exit for the methods a {@code MethodSelector} names.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class MethodDispatch {

  private final DispatchEngine engine;

  MethodDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called from advice woven into a method a {@code MethodSelector} names, before its body runs.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor, matched by {@code signaturePattern}
   * @throws Throwable if a matching scenario injects an exception
   */
  public void beforeMethodEnter(
      final Class<?> declaringType, final String methodName, final String descriptor)
      throws Throwable {
    if (engine.idle(OperationType.METHOD_ENTER)) {
      return;
    }
    final String className = declaringType.getName();
    engine.applyPreDecision(
        engine.evaluate(
            new InvocationContext(
                OperationType.METHOD_ENTER,
                className,
                className,
                methodName,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                InvocationContext.UNKNOWN,
                null,
                null,
                InvocationContext.UNKNOWN,
                descriptor,
                InvocationContext.UNKNOWN)));
  }

  /**
   * Called from advice woven into a method a {@code MethodSelector} names, after it returned
   * normally.
   *
   * @param declaringType the class declaring the method
   * @param methodName the method name
   * @param descriptor the method's JVM descriptor; its return type guides return-value corruption
   * @param actualValue the value the method returned, boxed; {@code null} for a void method
   * @return the (possibly corrupted) value to return
   * @throws Throwable if a matching scenario fails the call
   */
  public @Nullable Object afterMethodExit(
      final Class<?> declaringType,
      final String methodName,
      final String descriptor,
      final Object actualValue)
      throws Throwable {
    if (engine.idle(OperationType.METHOD_EXIT)) {
      return actualValue;
    }
    final String className = declaringType.getName();
    return afterMethodExit(
        new InvocationContext(
            OperationType.METHOD_EXIT,
            className,
            className,
            methodName,
            false,
            null,
            null,
            engine.scopeContext.currentSessionId(),
            InvocationContext.UNKNOWN,
            null,
            null,
            InvocationContext.UNKNOWN,
            descriptor,
            InvocationContext.UNKNOWN),
        MethodDescriptors.returnType(declaringType, descriptor),
        actualValue);
  }

  /**
   * Called from advice at method entry to apply any scenario targeting the given class and method.
   *
   * @param className the fully qualified name of the class containing the advised method
   * @param methodName the name of the advised method
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.METHOD_ENTER)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.METHOD_ENTER,
                    className,
                    className,
                    methodName,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called from advice at method exit to optionally corrupt the return value per active scenarios.
   *
   * @param className the fully qualified name of the class containing the advised method
   * @param methodName the name of the advised method
   * @param returnType the declared return type used to guide corruption strategies
   * @param actualValue the real return value produced by the method; may be {@code null}
   * @return the (possibly corrupted) value to return to the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public @Nullable Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    if (engine.idle(OperationType.METHOD_EXIT)) {
      return actualValue;
    }
    return afterMethodExit(
        new InvocationContext(
            OperationType.METHOD_EXIT,
            className,
            className,
            methodName,
            false,
            null,
            null,
            engine.scopeContext.currentSessionId()),
        returnType,
        actualValue);
  }

  private @Nullable Object afterMethodExit(
      final InvocationContext context, final Class<?> returnType, final Object actualValue)
      throws Throwable {
    final RuntimeDecision decision = engine.evaluate(context);
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.THROW) {
      // A reject on METHOD_EXIT fails the call after its body ran (side effects done, result
      // lost). It used to be dropped: only CORRUPT_RETURN was handled here.
      throw decision.terminalAction().failure();
    }
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.CORRUPT_RETURN) {
      // Sleep BEFORE substituting the corrupted return. RuntimeDecision documents "delay first,
      // then gate, then terminal action" and CORRUPT_RETURN still returns normally (unlike
      // THROW), so a composed "delay + corrupt" scenario must deliver both effects. Previously
      // the early return skipped engine.sleep(), silently dropping the timing signal operators were
      // testing for.
      final ChaosEffect.ReturnValueCorruptionEffect corruptEffect =
          (ChaosEffect.ReturnValueCorruptionEffect)
              java.util.Objects.requireNonNull(decision.terminalAction().returnValue());
      // Propagate the scenario id (stored on the TerminalAction at construction time in
      // terminalActionFor) so that corruption fallbacks like "EMPTY → ZERO" name the scenario
      // that fired rather than the literal string "method-exit".
      final String scenarioId = decision.terminalAction().scenarioId();
      return ReturnValueCorruptor.corrupt(
          corruptEffect.strategy(),
          returnType,
          actualValue,
          scenarioId != null ? scenarioId : "method-exit");
    }
    return actualValue;
  }
}
