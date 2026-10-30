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
| `APPLIED`    | an effect took effect                                                                                                        | `operation`, `scope`, `effectType`, …        |
| `SKIPPED`    | the selector matched but the policy kept the effect from applying — published only while a listener is registered           | `reason` (`notYetActive`, `warmUp`, `probability`, `rateLimit`, `maxApplications`), `operation`, `scope` |
| `RELEASED`   | a gate was released                                                                                                          | `scope`                                      |
| `STOPPED`    | the scenario stopped                                                                                                         | `scope`                                      |
| `FAILED`     | an activation failed (also recorded in the diagnostics)                                                                     | `category` (`INVALID_CONFIGURATION`, `UNSUPPORTED_RUNTIME`, `ACTIVATION_CONFLICT`, `INSTRUMENTATION_FAILURE`) |

## 3. Metrics

The optional module `chaos-agent-micrometer` binds chaos activity to a Micrometer `MeterRegistry`:

```java
new ChaosMeterBinder(ChaosPlatform.current()).bindTo(meterRegistry);
```

- `chaos.events` — counter per event, tagged `type` and `scenario`; `skipped` adds `reason`, `failed` adds `category`.
- `chaos.scenarios` — gauge of registered scenarios per `state`.

Events are counted asynchronously. Export to OpenTelemetry through Micrometer's OTLP registry.

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
