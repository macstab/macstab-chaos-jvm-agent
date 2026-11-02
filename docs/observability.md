# Observability

> How to see what the agent loaded and what it did: the status and hits files, events, metrics, JMX and handle counters.

---

## 1. Status and hits files

The same two files the native chaos libraries write, in the same format, so one tool reads every layer.

**Status file** `<dir>/.chaos-jvm.status`, rewritten after every load of the startup plan and every reload:

```text
state=error
scenarios=0
line=3
column=28
reason=invalid value: id must be non-blank at scenarios[0]
source=file:/etc/chaos/plan.json
pid=4711
```

- `state=ok` with `scenarios=<n>`, `source` and `pid` on success.
- On a rejected plan: `line` and `column` (1-based; `0` when the failure has no position, e.g. a validation error at activation), `reason` (what is wrong, without input values), `source`, `pid`. The agent refuses to start with a rejected startup plan; a rejected reload keeps the previous plan active.
- Without a startup plan the status file is not written.

**Hits file** `<dir>/.chaos-jvm.hits.<pid>`, one line per scenario:

```text
2	slow-writes
0	reject-orders
```

- `<applied count><TAB><scenario id>`; the count is the scenario's `appliedCount`.
- Written after each of the first 64 applications, then at most every 100 ms while applications happen, and at exit. A JVM in which nothing was ever applied writes none. Files of finished JVMs stay: sum all `.chaos-jvm.hits.<pid>` files for totals and remove them between runs.

Both files are written to a staging file and renamed, so a reader never sees a partial report; the writes run with the dispatch depth raised, so a file I/O scenario never applies to them.

| Setting                | Agent arg    | System property            | Environment variable         | Default            |
|------------------------|--------------|----------------------------|------------------------------|--------------------|
| Switch the files off   | `reports=off`| `macstab.chaos.reports`    | `MACSTAB_CHAOS_REPORTS`      | on                 |
| Directory              | `reportsDir` | `macstab.chaos.reportsDir` | `MACSTAB_CHAOS_REPORTS_DIR`  | `java.io.tmpdir`   |

## 2. Events

`ChaosControlPlane.addEventListener` delivers every event synchronously on the thread that triggered it; wrap a listener in `ChaosEventListener.async(listener)` to receive events on an agent-owned thread instead (bounded queue of 10 000; overflow is dropped and counted in the diagnostics' `droppedEvents`). `removeEventListener` removes a listener as it was registered.

| Type         | When                                                                                                                         | Attributes                                   |
|--------------|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `REGISTERED` | a scenario was registered, before it starts                                                                                  | `scope`                                      |
| `STARTED`    | the scenario became `ACTIVE`                                                                                                 | `scope`                                      |
| `APPLIED`    | an effect took effect                                                                                                        | `operation`, `scope`, `effectType`, `match` (the scenario's 1-based match number), `delayMillis` (when it added a delay), `sessionId` (session scenarios) |
| `SKIPPED`    | the selector matched but the policy kept the effect from applying — published only while a listener is registered           | `reason` (`notYetActive`, `warmUp`, `probability`, `rateLimit`, `maxApplications`), `operation`, `scope` |
| `RELEASED`   | a gate was released                                                                                                          | `scope`                                      |
| `STOPPED`    | the scenario stopped                                                                                                         | `scope`                                      |
| `FAILED`     | an activation failed (also recorded in the diagnostics)                                                                     | `category` (`INVALID_CONFIGURATION`, `UNSUPPORTED_RUNTIME`, `ACTIVATION_CONFLICT`, `INSTRUMENTATION_FAILURE`) |

## 3. Metrics

Two optional modules turn events into metrics. Each instrument has one fixed set of tags (Prometheus rejects a name registered with differing tag keys).

| Metric                      | Type                  | Tags (Micrometer)                    | Measures                                                          |
|-----------------------------|-----------------------|--------------------------------------|-------------------------------------------------------------------|
| `chaos.events`              | counter               | `type`, `scenario`                   | every event                                                       |
| `chaos.applications`        | counter               | `scenario`, `operation`, `effect`    | effects that took effect                                          |
| `chaos.injected.delay`      | timer / histogram (ms) | `scenario`, `operation`             | latency the agent added to calls (from the `APPLIED` event's `delayMillis`) |
| `chaos.skips`               | counter               | `scenario`, `reason`                 | matches the policy kept from applying (while a listener is registered) |
| `chaos.activation.failures` | counter               | `scenario`, `category`               | failed activations                                                |
| `chaos.scenarios`           | gauge                 | `state`                              | registered scenarios per state                                    |
| `chaos.events.dropped`      | counter               | —                                    | events dropped by full asynchronous listener queues               |
| `chaos.hooks.woven`         | gauge                 | —                                    | hook groups woven into JDK and library classes                    |

**Micrometer** (`chaos-agent-micrometer`):

```java
ChaosMeterBinder binder = new ChaosMeterBinder(ChaosPlatform.current());
binder.bindTo(meterRegistry);   // binder.close() stops counting
```

**OpenTelemetry** (`chaos-agent-opentelemetry`), the same instruments with attributes `chaos.event.type`, `chaos.scenario.id`, `chaos.operation`, `chaos.effect`, `chaos.skip.reason`, `chaos.failure.category`, `chaos.scenario.state`, scope `com.macstab.chaos.jvm`:

```java
AutoCloseable metrics = ChaosOpenTelemetry.metrics(ChaosPlatform.current(), openTelemetry);
AutoCloseable spans = ChaosOpenTelemetry.spanEvents(ChaosPlatform.current());
```

`spanEvents` adds a `chaos.applied` event (scenario, operation, effect, `chaos.delay_ms`) to the current span each time chaos applies to a call made inside it, so a trace shows which request was slowed or failed and by which scenario. It runs on the application thread, where the span is current; the counters are fed asynchronously.

Both modules declare their library as `compileOnly`: the application brings Micrometer or OpenTelemetry.

## 4. JMX

The MBean `com.macstab.chaos:type=ChaosDiagnostics` offers:

| Operation                 | Always available | Description                                                         |
|---------------------------|------------------|---------------------------------------------------------------------|
| `debugDump()`             | yes              | Human-readable dump of every scenario                               |
| `listScenarios()`         | yes              | `<id> state=<state> matched=<n> applied=<n>` per scenario           |
| `isControlEnabled()`      | yes              | Whether the operations below are allowed                            |
| `activatePlan(json)`      | no               | Activates a plan (startup-config JSON); returns the scenario ids    |
| `stopScenario(id)`        | no               | Stops a scenario activated through `activatePlan`                   |
| `stopAll()`               | no               | Stops every scenario activated through `activatePlan`               |

The control operations are off by default — anyone who can reach the MBean server could otherwise inject faults. Enable them with the agent argument `jmxControl=true`, `-Dmacstab.chaos.jmx.control=true` or `MACSTAB_CHAOS_JMX_CONTROL=true`, and secure the JMX connector.

## 5. Handle counters

`ChaosActivationHandle.matchedCount()` and `appliedCount()` return the scenario's counters (the sum over its scenarios for a plan handle), so a test can assert on the handle it activated:

```java
try (ChaosActivationHandle handle = chaos.activate(scenario)) {
    callTheService();
    assertThat(handle.appliedCount()).isPositive();
}
```

The diagnostics' `runtimeDetails()` also report `wovenHookGroups` (see [`instrumentation.md`](instrumentation.md)) and `droppedEvents`.
