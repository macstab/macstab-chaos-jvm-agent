# Unreleased — 2.0.0


### BREAKING CHANGES

* **instrumentation:** hooks are woven on demand. The agent no longer weaves every hook at start: each interception point belongs to a hook group (`JdkInstrumentationInstaller.HookGroup`) that is retransformed the first time an activated scenario (or an opened session) needs one of its operation types, and stays woven until uninstall. A JVM without scenarios runs unmodified JDK classes; the first activation of a hook group pays its one-time weaving cost, and a weaving failure fails that activation with failure category `INSTRUMENTATION_FAILURE`. The agent argument `instrumentation=eager`, the system property `-Dmacstab.chaos.instrumentation=eager` or the environment variable `MACSTAB_CHAOS_INSTRUMENTATION=eager` restores the 1.x behaviour (every group woven at start).
* **instrumentation:** the premain / agentmain split is gone. Dynamic attach (`agentmain`, still behind its opt-in) and `installForLocalTests()` get exactly the same hook set as `-javaagent:` — sockets, NIO, HTTP, JDBC, clock and the rest are no longer premain-only. `ChaosAgentBootstrap.initialize(agentArgs, instrumentation, environment)` lost its `premainMode` parameter, and `JdkInstrumentationInstaller.install` takes an `eager` flag instead.
* **instrumentation:** the unused handle slot `ADJUST_SCHEDULE_DELAY` (index 5) and `BridgeDelegate.adjustScheduleDelay` were removed. Every `BootstrapDispatcher` handle index above 5 shifted down by one; `HANDLE_COUNT` is now 57.
* **packaging:** the agent JAR (`chaos-agent-bootstrap`) relocates ByteBuddy and Jackson to `com.macstab.chaos.jvm.shaded.*`, so they can no longer clash with the application's own copies (Mockito, Hibernate, Spring). Its published POM declares no dependencies: everything it needs is inside the JAR. The Spring Boot starters no longer depend on `chaos-agent-core` and `chaos-agent-startup-config` at runtime; those classes come from the agent JAR.
* **api:** `chaos-agent-api` no longer depends on `jackson-annotations`. The JSON shape of plans (the `type` discriminators, the `ActivationPolicy` and `NamePattern` creators) is defined by mix-ins in `chaos-agent-startup-config`; serialise and parse plans with `ChaosPlanMapper`, another `ObjectMapper` no longer knows the discriminators.
* the legacy duplicate package tree `com.macstab.chaos.*` (everything outside `com.macstab.chaos.jvm.*`) was removed. Use the `com.macstab.chaos.jvm.*` types.


### Features

* **diagnostics:** `ChaosDiagnostics.snapshot().runtimeDetails()` contains `wovenHookGroups` — the comma-separated, sorted names of the hook groups woven so far (empty when none).


### Performance Improvements

* **core:** `ScenarioRegistry` keeps an immutable index from operation type to controllers, pre-sorted by descending precedence then id and swapped on register/unregister. `match()` evaluates only the scenarios whose selector names the intercepted operation and no longer sorts per call; clock-skew reads use the same index and return immediately when no scenario names the clock.
* **instrumentation:** the dispatch path allocates nothing per call. `BootstrapDispatcher` no longer routes through the shared `invoke(ThrowingSupplier, fallback)` trampoline (whose call site saw every lambda, so the JIT could not elide the 24-byte capturing lambda allocated per intercepted call); each dispatch method guards reentrancy inline. `ThrowingSupplier` was removed from the bootstrap bridge.
* **api:** `NamePattern` GLOB matching uses an allocation-free wildcard matcher instead of the compiled regex for ordinary candidates, with identical results (candidates containing line terminators or surrogate characters still use the regex).


# 1.0.0 (2026-04-29)


### Bug Fixes

* resolve ThreadLocal reentrancy - re-enable ThreadLocal/park/AQS instrumentation ([738d4ed](https://github.com/macstab/macstab-chaos-jvm-agent/commit/738d4ed3613f0f67483bc4323febdc77201a57b3))


### Features

* final changes required for inital version  ([b62fb6d](https://github.com/macstab/macstab-chaos-jvm-agent/commit/b62fb6d77650ddaee5d3d63bbd8457c2213f8323)), closes [#1](https://github.com/macstab/macstab-chaos-jvm-agent/issues/1) [#2](https://github.com/macstab/macstab-chaos-jvm-agent/issues/2) [#3](https://github.com/macstab/macstab-chaos-jvm-agent/issues/3) [JdkInstrumentationInstaller#install](https://github.com/JdkInstrumentationInstaller/issues/install)
* implement missing stressors, ClockSkew, ExceptionInjection, ReturnValueCorruption, fix state machine, registry cleanup, maxApplications race, logging levels ([250413a](https://github.com/macstab/macstab-chaos-jvm-agent/commit/250413ad85539b5e66357c6bd2623823ca4cad73))
* initial chaos testing Java agent implementation ([68eff23](https://github.com/macstab/macstab-chaos-jvm-agent/commit/68eff238fc09d6d5bc6a22127fc07414ce56037a))
* phase 2 - full JVM chaos coverage across 30+ new interception points ([6132125](https://github.com/macstab/macstab-chaos-jvm-agent/commit/6132125b367a24518b78f2ed96ea76dd0494b9ed))
