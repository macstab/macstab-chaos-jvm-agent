# Contributing

## Prerequisites

- JDK 21 or later at runtime (JDK 25 recommended for building — the toolchain downloads it automatically via Gradle)
- No other installation required; `./gradlew` bootstraps everything

## Build & test

```bash
./gradlew build                        # compile, format-check, test, Jacoco and coverage floors
./gradlew test -PtestJavaVersion=21    # run the tests on another installed JDK (CI: 21 and 25, amd64 and arm64)
CHAOS_DSL_PATH=../macstab-chaos-testing-libraries/docs/DSL.md \
  ./gradlew :chaos-agent-startup-config:test --tests '*CrossLayerDocSyncTest*'   # check the native library's DSL.md against this API
./gradlew spotlessApply                # auto-fix formatting before committing
./gradlew aggregatedJavadoc            # build API docs at build/docs/aggregated-javadoc/
./gradlew :chaos-agent-core:test       # run tests for a single module
```

Tests for the instrumentation and bootstrap modules start a real agent (self-attach). They require no flags but do need the process to be attachable (`-Djdk.attach.allowAttachSelf=true` is wired automatically by the test extension).

## Quality gates

`./gradlew build` runs every gate below; each one fails the build, and each generated file is regenerated with the command shown, so an intended change shows in review as a diff.

| Gate | Guards | Record an intended change |
|------|--------|---------------------------|
| `apiCheck` | the public API of every API module (`api/<module>.api`, JVM signatures) | `./gradlew apiDump` |
| `PlanSchemaTest` | the published plan schema is the parser's | `./gradlew :chaos-agent-startup-config:test --tests '*PlanSchemaTest*' -PupdatePlanSchema` |
| `CompatibilityMatrixDocTest` | the effect/operation table is the validator's | `./gradlew :chaos-agent-core:test --tests '*CompatibilityMatrixDocTest*' -PupdateDocs` |
| `ReferenceTablesSyncTest`, `HandleTableDocTest`, `CrossLayerDocSyncTest` | reference tables, handle counts, API names and JSON examples in the docs | edit the docs |
| `SemanticsLawTraceabilityTest` | every law of `docs/semantics.md` has a test tagged `law-L<n>` | tag the test |
| `ArchitectureTest` | the bridge jar is closed, advice uses only public members, layering | — |
| Error Prone (`-Werror`) | static analysis of every compilation; a finding fails the build | fix it, or suppress it where it stands with the reason |
| `PlanFuzzTest` | the parser and validator never crash or hang | replay a failure with `-PfuzzSeed=<seed> -PfuzzIterations=<n>` |
| JaCoCo floors | line and branch coverage per module, child JVMs included | raise the floor in `build.gradle.kts`, never lower it |

Not part of `build`, run before a change to evaluation or validation code:

```bash
./gradlew pitest                    # mutation testing of api, core and startup-config; reports in <module>/build/reports/pitest
scripts/check-reproducible.sh       # builds every jar twice and fails when any differs
```

The decisions behind the architecture are recorded in [docs/adr](docs/adr/README.md); a change that reverses one adds a record that supersedes it.

## Code style

Spotless enforces **Google Java Format**. Run `./gradlew spotlessApply` before pushing. The CI build fails on any formatting violation.

## Commit conventions

This project uses [Conventional Commits](https://www.conventionalcommits.org/) for automated semantic versioning:

| Prefix | Effect |
|--------|--------|
| `feat:` | bumps MINOR version |
| `fix:` / `perf:` | bumps PATCH version |
| `feat!:` / `fix!:` or `BREAKING CHANGE:` footer | bumps MAJOR version |
| `chore:` / `docs:` / `test:` / `refactor:` | no version bump |

The release workflow runs `semantic-release` automatically after a successful build on `main`.

---

## Module map

| Module | What lives here |
|--------|----------------|
| `chaos-agent-api` | Public contract: `ChaosControlPlane`, `ChaosSession`, selectors, effects, `ChaosScenario` |
| `chaos-agent-core` | Runtime engine: `ScenarioRegistry`, `ScenarioController`, all stressors |
| `chaos-agent-instrumentation-jdk` | ByteBuddy advice classes, bootstrap bridge, `JdkInstrumentationInstaller` |
| `chaos-agent-bootstrap` | `premain`/`agentmain`, self-attach, MBean, config poller |
| `chaos-agent-startup-config` | JSON config loading and file-path hardening |
| `chaos-agent-testkit` | JUnit 5 `@ChaosTest` extension and `ChaosSession` injection |
| `chaos-agent-spring-boot3/4-starter` | Runtime Spring Boot integration and Actuator endpoint |
| `chaos-agent-spring-boot3/4-test-starter` | `@ChaosTest` for Spring Boot tests |
| `chaos-agent-spring-boot-common` | Shared Spring logic (one source, two compile targets) |
| `chaos-agent-micronaut-integration` | Micronaut integration |
| `chaos-agent-quarkus-extension` | Quarkus extension (runtime + deployment modules) |
| `chaos-agent-benchmarks` | JMH benchmarks — not published |
| `chaos-agent-examples` | Reference applications — not published |

---

## Adding a new interception point

This is the most constrained part of the codebase. Read this section before touching `chaos-agent-instrumentation-jdk`.

### 1. Understand the classloader split

`BootstrapDispatcher` lives in the **bootstrap classloader** (injected via a temporary JAR). Every advice class is **inlined verbatim** into the target JDK method by ByteBuddy — it is not called as a method. This means:

- **No instance fields** in advice classes. All state must be local variables or statics on `BootstrapDispatcher`.
- **No references to agent-classloader types** from advice bytecode. The bootstrap classloader cannot see `com.macstab.chaos.jvm.core.*` or any ByteBuddy type except what is explicitly in the bootstrap JAR.
- **Only `BootstrapDispatcher` methods** may be called from `@Advice` methods. Route everything through one of its `before*` / `after*` / `decorate*` / `adjust*` methods.
- **`@Advice` methods must be `static`** — ByteBuddy inlines them; instance dispatch is impossible.

### 2. Add the operation tag

Add a constant to the appropriate enum or constant class in `chaos-agent-api` (e.g., `JvmOperation`). This is the string your selector will match.

### 3. Implement the `BootstrapDispatcher` entry point

Add a `static` method to `BootstrapDispatcher` that:
1. Checks the reentrancy guard (`DEPTH.get() > 0 → return immediately`)
2. Increments depth in a `try/finally`
3. Delegates to the bridge: `delegate.beforeXxx(operationTag, ...)`
4. Decrements depth in `finally`

Copy the pattern from any existing `before*` method exactly. The reentrancy guard is not optional — omitting it causes infinite recursion when your advice fires inside a method the agent itself calls.

### 4. Write the advice class

Add an inner `static final class` to the appropriate `*Advice.java` file (or create a new one):

```java
static final class MyOperationAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.This Object target, @Advice.Argument(0) Object arg)
            throws Throwable {
        BootstrapDispatcher.beforeMyOperation("MY_OPERATION_TAG", target, arg);
    }
}
```

Rules:
- `@Advice.OnMethodEnter` for pre-invocation; `@Advice.OnMethodExit` for post-invocation
- Arguments must match the target method's parameter types exactly (position, type, variance)
- To modify an argument, add `readOnly = false` to `@Advice.Argument` and assign to it
- Never call anything that could trigger the advice you're installing (causes reentrancy)

### 5. Register the transformation in `JdkInstrumentationInstaller`

In `install()`, add a `.type(...).transform(...)` entry to the `AgentBuilder` chain:

```java
.type(ElementMatchers.named("java.some.TargetClass"))
.transform((builder, type, loader, module, domain) ->
    builder.visit(Advice.to(MyOperationAdvice.class)
        .on(ElementMatchers.named("targetMethod")
            .and(ElementMatchers.takesArguments(MyParam.class)))))
```

Phase 1 points (always installed) go before the `if (premainMode)` check. Phase 2 points (require retransformation of already-loaded classes) go inside it.

### 6. Handle the dispatch in `ChaosDispatcher`

Add the corresponding `beforeMyOperation(...)` method that receives the call from `ChaosBridge` and routes it through `ScenarioRegistry` → `ScenarioController` evaluation. Follow the pattern of any existing `before*` method in `ChaosDispatcher`, including its `idle(operation)` check: return the unmodified result before building the `InvocationContext`, so a call without a scenario for its operation allocates nothing.

### 7. Test it

Add an integration test in `chaos-agent-bootstrap/src/test/` that:
1. Activates a scenario targeting `MY_OPERATION_TAG`
2. Calls the instrumented JDK method
3. Asserts the effect was applied (delay, exception, suppression, etc.)

The `ChaosAgentExtension` handles self-attach — no `-javaagent` flag needed in tests.

---

## Adding a new stressor

Stressors are simpler — they live entirely in `chaos-agent-core` and have no ByteBuddy involvement.

1. Implement `ManagedStressor` in `chaos-agent-core`
2. Allocate resources in `start()`, release them in `stop()`
3. For destructive stressors (deadlock, thread leak, permanent memory pressure), check `ChaosRuntime.isDestructiveEffectsEnabled()` and throw `IllegalStateException` if not enabled
4. Register the stressor type in the sealed effect hierarchy in `chaos-agent-api`
5. Wire activation in `ScenarioController`
6. Add a lifecycle test: verify `isActive()` transitions and that `stop()` releases resources

---

## Pull requests

- One logical change per PR
- Tests required for new interception points and stressors
- `./gradlew build` must pass locally before opening a PR. It enforces a line and branch coverage floor per module (`coverageFloors` in `build.gradle.kts`); raise a floor when your tests raise coverage, never lower one to make a change pass
- Documentation examples are checked: every API name, enum constant and plan JSON `type` in `README.md` and `docs/*.md` must exist, and every `json` plan or scenario must parse (`CrossLayerDocSyncTest`). Fence a schema sketch with placeholders as `jsonc`. A Java example fenced as `java verified` must be a complete compilation unit with a public type: the test compiles it against the API. When you rename API, update the native library's `docs/DSL.md` too; CI checks it against its `develop` branch
- Pull requests into `main` run the benchmark gate (`docs/benchmarks.md` §8): a dispatch benchmark more than 15 % and 25 ns slower than `main` (p < 0.01) fails it
- For breaking API changes, add a `BREAKING CHANGE:` footer to the commit message
