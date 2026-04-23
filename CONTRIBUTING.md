# Contributing

## Prerequisites

- JDK 21 or later at runtime (JDK 25 recommended for building — the toolchain downloads it automatically via Gradle)
- No other installation required; `./gradlew` bootstraps everything

## Build & test

```bash
./gradlew build                        # compile, format-check, test, Jacoco
./gradlew spotlessApply                # auto-fix formatting before committing
./gradlew aggregatedJavadoc            # build API docs at build/docs/aggregated-javadoc/
./gradlew :chaos-agent-core:test       # run tests for a single module
```

Tests for the instrumentation and bootstrap modules start a real agent (self-attach). They require no flags but do need the process to be attachable (`-Djdk.attach.allowAttachSelf=true` is wired automatically by the test extension).

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

Add the corresponding `beforeMyOperation(...)` method that receives the call from `ChaosBridge` and routes it through `ScenarioRegistry` → `ScenarioController` evaluation. Follow the pattern of any existing `before*` method in `ChaosDispatcher`.

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
- `./gradlew build` must pass locally before opening a PR
- For breaking API changes, add a `BREAKING CHANGE:` footer to the commit message
