package com.macstab.chaos.jvm.instrumentation;

import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.CLOCK_CALL_SITE_PACKAGES;
import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.advise;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.util.List;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The hook groups for {@code java.time} reads and {@code System.currentTimeMillis()} / {@code
 * nanoTime()} call sites: which methods of which types receive which advice. {@link
 * JdkInstrumentationInstaller.HookGroup} weaves them on demand.
 */
final class ClockHooks {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  private ClockHooks() {}

  /**
   * Higher-level time APIs. Direct System.currentTimeMillis() / System.nanoTime() cannot be
   * intercepted (see note in install()) but java.time.Instant.now(), java.time.LocalDateTime.now(),
   * java.time.ZonedDateTime.now(), and java.util.Date() are regular Java members that can be woven
   * at exit.
   */
  static AgentBuilder applyTimeApiTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.time.Instant"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClockAdvice.InstantNowAdvice.class,
                        ElementMatchers.named("now")
                            .and(ElementMatchers.isStatic())
                            .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.time.LocalDateTime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ClockAdvice.LocalDateTimeNowAdvice.class,
                            ElementMatchers.named("now")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(0))))
                    // now(ZoneId) goes to now(Clock) without calling now(); now(Clock) is not
                    // hooked because a caller-supplied clock (fixed, offset) must not be skewed.
                    .visit(
                        advise(
                            ClockAdvice.LocalDateTimeNowInZoneAdvice.class,
                            ElementMatchers.named("now")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(java.time.ZoneId.class)))))
        .type(ElementMatchers.named("java.time.ZonedDateTime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                // The returned ZonedDateTime carries its zone, so one advice covers now() and
                // now(ZoneId).
                typeBuilder.visit(
                    advise(
                        ClockAdvice.ZonedDateTimeNowAdvice.class,
                        ElementMatchers.named("now")
                            .and(ElementMatchers.isStatic())
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(ElementMatchers.takesArguments(java.time.ZoneId.class))))))
        // The system clock and instant source: every other java.time now() reads one of them.
        .type(
            ElementMatchers.namedOneOf(
                "java.time.Clock$SystemClock", "java.time.Clock$SystemInstantSource"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ClockAdvice.SystemClockInstantAdvice.class,
                            ElementMatchers.named("instant")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ClockAdvice.SystemClockMillisAdvice.class,
                            ElementMatchers.named("millis")
                                .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.util.Date"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClockAdvice.DateNewAdvice.class,
                        ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * Clock call sites: in classes under the configured prefixes, every {@code invokestatic} of
   * {@link System#currentTimeMillis()} and {@link System#nanoTime()} becomes a call of {@link
   * BootstrapDispatcher#currentTimeMillis()} / {@link BootstrapDispatcher#nanoTime()}, which return
   * the real value through the clock-skew dispatch. Loaded classes are retransformed; method bodies
   * change, nothing else.
   */
  static AgentBuilder applyClockCallSiteTransformations(final AgentBuilder agentBuilder) {
    final List<String> packages = CLOCK_CALL_SITE_PACKAGES;
    if (packages.isEmpty()) {
      // A SYSTEM_CLOCK_* scenario is valid without the setting (manual adjustClockMillis hooks),
      // but direct System.currentTimeMillis() calls stay unskewed: say so instead of doing
      // nothing silently.
      LOGGER.warning(
          "chaos: a SYSTEM_CLOCK_MILLIS/NANOS scenario is active but clockCallSites is not set;"
              + " direct System.currentTimeMillis()/nanoTime() calls are not skewed");
      return agentBuilder;
    }
    ElementMatcher.Junction<TypeDescription> types = ElementMatchers.none();
    for (final String prefix : packages) {
      types = types.or(ElementMatchers.nameStartsWith(prefix));
    }
    final TypeDescription dispatcher = TypeDescription.ForLoadedType.of(BootstrapDispatcher.class);
    final net.bytebuddy.description.method.MethodDescription skewedMillis =
        dispatcher
            .getDeclaredMethods()
            .filter(ElementMatchers.named("currentTimeMillis"))
            .getOnly();
    final net.bytebuddy.description.method.MethodDescription skewedNanos =
        dispatcher.getDeclaredMethods().filter(ElementMatchers.named("nanoTime")).getOnly();
    return agentBuilder
        // Interfaces too: their default and static methods and the lambdas declared in them hold
        // clock calls as well.
        .type(types)
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    net.bytebuddy.asm.MemberSubstitution.relaxed()
                        .method(
                            ElementMatchers.named("currentTimeMillis")
                                .and(ElementMatchers.isDeclaredBy(System.class))
                                .and(ElementMatchers.takesArguments(0)))
                        .replaceWith(skewedMillis)
                        .method(
                            ElementMatchers.named("nanoTime")
                                .and(ElementMatchers.isDeclaredBy(System.class))
                                .and(ElementMatchers.takesArguments(0)))
                        .replaceWith(skewedNanos)
                        .on(ElementMatchers.any())));
  }
}
