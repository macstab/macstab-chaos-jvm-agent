package com.macstab.chaos.jvm.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, method, field, or constructor as part of the chaos-agent's internal implementation.
 *
 * <p>Members annotated with {@code @Internal} are deliberately <strong>excluded</strong> from the
 * public, backwards-compatible API surface of the chaos-agent. They may change, be renamed, or be
 * removed in any release — including patch releases — without prior notice, deprecation cycle, or
 * migration guide. Downstream code must not bind to {@code @Internal} members; if a piece of
 * functionality is needed externally it should be promoted to a stable, unannotated type (typically
 * in {@code com.macstab.chaos.jvm.api.*}) first.
 *
 * <p>The annotation is retained at {@link RetentionPolicy#CLASS} so it is visible to bytecode-level
 * tools such as API linters, Gradle {@code japicmp} tasks, and IDE inspections, but is not paid for
 * at runtime. Because the chaos-agent core must run on consumer classpaths that typically do not
 * depend on {@code chaos-agent-api} transitively (shaded agents), this annotation intentionally
 * lives in the api module so it is always available wherever public-facing chaos types live.
 *
 * <p><strong>Rationale for existence.</strong> Several classes in {@code chaos-agent-core} —
 * notably {@code ChaosRuntime} and {@code ChaosDispatcher} — are {@code public} because they are
 * referenced across the internal multi-module split (bootstrap, instrumentation, benchmarks) but
 * are <em>not</em> part of the frozen 1.0 API contract. Marking them {@code @Internal} makes that
 * distinction explicit both to humans and to tooling, so the compatibility story for each release
 * can focus on the stable {@code com.macstab.chaos.jvm.api.*} surface without accidentally freezing
 * every cross-module bridge method.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Internal
 * public final class ChaosRuntime implements ChaosControlPlane { ... }
 * }</pre>
 *
 * <h2>See also</h2>
 *
 * <ul>
 *   <li>{@code ChaosControlPlane} — the stable public entry point. Prefer binding against this
 *       interface rather than any {@code @Internal} class.
 *   <li>Apache Commons / Spring Framework's {@code @Internal} and JetBrains'
 *       {@code @ApiStatus.Internal} use the same convention.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
  ElementType.TYPE,
  ElementType.METHOD,
  ElementType.CONSTRUCTOR,
  ElementType.FIELD,
  ElementType.PACKAGE
})
public @interface Internal {}
