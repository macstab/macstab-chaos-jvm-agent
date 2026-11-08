package com.macstab.chaos.jvm.core;

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
      final @Nullable Object actualValue)
      throws Throwable {
    if (engine.idle(OperationType.METHOD_EXIT)) {
      return actualValue;
    }
    final String className = declaringType.getName();
    final Class<?> returnType = MethodDescriptors.returnType(declaringType, descriptor);
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
        returnType != MethodDescriptors.UNRESOLVED ? returnType : null,
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
   * @param returnType the declared return type used to guide corruption strategies; {@code null} or
   *     {@code void.class} for a method without a value, which no corruption applies to
   * @param actualValue the real return value produced by the method; may be {@code null}
   * @return the (possibly corrupted) value to return to the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public @Nullable Object afterMethodExit(
      final String className,
      final String methodName,
      final @Nullable Class<?> returnType,
      final @Nullable Object actualValue)
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
      final InvocationContext context,
      final @Nullable Class<?> returnType,
      final @Nullable Object actualValue)
      throws Throwable {
    final RuntimeDecision decision =
        engine.evaluate(context, returnType != null && returnType != void.class);
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() instanceof TerminalAction.Throw thrown) {
      // A reject on METHOD_EXIT fails the call after its body ran (side effects done, result
      // lost).
      throw thrown.failure();
    }
    if (decision.terminalAction() instanceof TerminalAction.CorruptReturn corrupting) {
      // The delay and the gate above have already run: a composed "delay + corrupt" scenario
      // delivers both. Fallbacks such as "EMPTY -> ZERO" name the scenario that fired.
      return ReturnValueCorruptor.corrupt(
          corrupting.effect().strategy(), returnType, actualValue, corrupting.scenarioId());
    }
    return actualValue;
  }
}
