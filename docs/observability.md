# Observability

> How to see what the agent loaded and what it did: the status and hits files, events, metrics, JMX and handle counters.

---

## 1. Status and hits files

The same two files the native chaos libraries write, in the same format, so one tool reads every layer. They are written only when switched on with `reports=on` (or any other true literal: `true`, `1`, `yes`); the first write that fails is logged once as a warning naming the directory.

**Status file** `<dir>/.chaos-jvm.status`, rewritten after every load of the startup plan and every reload; a reload failure that recurs on every tick (an id taken elsewhere) is written once, not on every tick:

```text
state=error
scenarios=0
line=3
column=28
reason=invalid value: id must be non-blank at scenarios[0]
source=file:/etc/chaos/plan.json
pid=4711
```

- `state=ok` with `scenarios=<n>`, `source` and `pid` on success. A `file:` source is the plan file's absolute path, also when it was configured relative.
- On a rejected plan: `line` and `column` (1-based; `0` when the failure has no position, e.g. a validation error at activation), `reason` (what is wrong, without input values; the exception's class name when it has no message), `source`, `pid`. The agent refuses to start with a rejected startup plan; a rejected reload keeps the previous plan active.
- Without a startup plan the status file is not written.

**Hits file** `<dir>/.chaos-jvm.hits.<pid>`, one line per scenario id that applied, sorted by id:

```text
5	reject-orders
2	slow-writes
```

- `<applied count><TAB><scenario id>`; the count is cumulative since the agent started: a stopped, expired or reloaded scenario keeps its count, and the same id in several sessions is one line with their sum.
- Written after each of the first 64 applications, then at most every 100 ms while applications happen, and at exit. A JVM in which nothing was ever applied writes none. Files of finished JVMs stay: sum all `.chaos-jvm.hits.<pid>` files for totals and remove them between runs.

Both files are written to a staging file and renamed, so a reader never sees a partial report; the writes run with the dispatch depth raised, so a file I/O scenario never applies to them. An existing report is replaced or removed only when it is a regular file owned by the JVM's user (the owner is compared on file systems with a POSIX view): in a directory other users can write without the sticky bit, the agent never overwrites or deletes their files, and a refused file is logged at `FINE`.

Differences from the native library, and limits of shared directories:

- **No `0` lines.** The library writes a line for every loaded rule, `0` for one that never matched; the agent lists only ids that applied, and keeps an id after its scenario ends. Read the status file's `scenarios` (and the diagnostics) for what was loaded.
- **One status file per directory.** JVMs that share a `reportsDir` (forked test JVMs, several services) overwrite each other's status; the `pid` line names the last writer. Give each JVM its own `reportsDir`.
- **pid 1 in containers.** Containers that share a mounted `reportsDir` usually all run their JVM as pid 1, so their hits files have the same name and replace each other (the library picks another suffix; the agent does not). Give each container its own `reportsDir`.
- **200 characters.** Every text value (a scenario id, `source`, `reason`) is cut to its first 200 characters after control characters become spaces, as the library cuts rule texts to 200 bytes; two ids that differ only after that appear as two lines with the same text.

| Setting                | Agent arg    | System property            | Environment variable         | Default            |
|------------------------|--------------|----------------------------|------------------------------|--------------------|
| Switch the files on    | `reports=on` | `macstab.chaos.reports`    | `MACSTAB_CHAOS_REPORTS`      | off                |
| Directory              | `reportsDir` | `macstab.chaos.reportsDir` | `MACSTAB_CHAOS_REPORTS_DIR`  | `java.io.tmpdir`   |

## 2. Events

`ChaosControlPlane.addEventListener` delivers every event synchronously on the thread that triggered it; wrap a listener in `ChaosEventListener.async(listener)` to receive events on an agent-owned thread instead (bounded queue of 10 000; overflow is dropped and counted in the diagnostics' `droppedEvents`). `removeEventListener` removes a listener as it was registered. Removing an asynchronous listener (or closing the control plane) lets the event it is handling complete, never interrupting it, and counts the events still queued in `droppedEvents`; it waits for that event at most 5 s (one WARNING if the listener has not returned by then) and not at all when the listener removes itself from `onEvent`. A listener that throws is logged at WARNING once, then at FINE.

| Type         | When                                                                                                                         | Attributes                                   |
|--------------|------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------|
| `REGISTERED` | a scenario was registered, before it starts                                                                                  | `scope`                                      |
| `STARTED`    | the scenario became `ACTIVE`                                                                                                 | `scope`                                      |
| `APPLIED`    | an effect took effect                                                                                                        | `operation`, `match` (the scenario's 1-based match number), `scope`, `effectType`, `delayMillis` (when it added a delay), `sessionId` (session scenarios) |
| `SKIPPED`    | the selector matched but the policy kept the effect from applying — published only while a listener is registered           | `reason` (`notYetActive`, `warmUp`, `probability`, `rateLimit`, `maxApplications`), `operation`, `scope` |
| `RELEASED`   | a gate was released                                                                                                          | `scope`                                      |
| `INACTIVE`   | the scenario ended on its own (`ACTIVE → INACTIVE`), once                                                                    | `scope`, `reason` (`expired`, `max applications reached`, and for a stressor `stressor failed: <type>`, `duration elapsed`, `lifespan elapsed`) |
| `STOPPED`    | the scenario stopped: once per transition into `STOPPED` (a second `stop()`, or `close()` after `stop()`, publishes nothing) | `scope`                                      |
| `FAILED`     | an activation failed (also recorded in the diagnostics)                                                                     | `category` (`INVALID_CONFIGURATION`, `UNSUPPORTED_RUNTIME`, `ACTIVATION_CONFLICT`, `INSTRUMENTATION_FAILURE`), `scope` |

An event's attributes iterate in the order listed above, the same in every JVM, and the diagnostics' `runtimeDetails` in the order they are reported; a selector's `operations` iterate (and are written to JSON) in the declaration order of `OperationType`. Every event is also logged through `java.util.logging` as one line, `type=X scenarioId="…" message="…" attributes={k="v", …}`, with every id, message, key and value escaped (see [configuration-reference §12.2](configuration-reference.md#122-structured-logging)).

## 3. Metrics

Two optional modules turn events into metrics. Each instrument has one fixed set of tags (Prometheus rejects a name registered with differing tag keys).

| Metric                      | Type                  | Tags (Micrometer)                    | Measures                                                          |
|-----------------------------|-----------------------|--------------------------------------|-------------------------------------------------------------------|
| `chaos.events`              | counter               | `type`, `scenario`                   | every event                                                       |
| `chaos.applications`        | counter               | `scenario`, `operation`, `effect`    | effects that took effect                                          |
| `chaos.injected.delay`      | timer / histogram (ms) | `scenario`, `operation`             | latency the agent added to calls (from the `APPLIED` event's `delayMillis`) |
| `chaos.skips`               | counter               | `scenario`, `reason`                 | matches the policy kept from applying (while a listener is registered) |
| `chaos.activation.failures` | counter               | `category`                           | failed activations                                                |
| `chaos.scenarios`           | gauge                 | `state`                              | registered scenarios per state                                    |
| `chaos.events.dropped`      | counter               | —                                    | events dropped by full asynchronous listener queues               |
| `chaos.hooks.woven`         | gauge                 | —                                    | hook groups woven into JDK and library classes                    |

**Micrometer** (`chaos-agent-micrometer`):

```java
ChaosMeterBinder binder = new ChaosMeterBinder(ChaosPlatform.current());
binder.bindTo(meterRegistry);   // binder.close() stops counting
```

The series tagged with a `scenario` are retired once the scenario has ended (`STOPPED`, `INACTIVE`, or a refused activation) and two scrapes (reads of the `chaos.scenarios` gauges) ran since, so its last values were exported: on the next chaos event after that, or at `close()`. An id still registered or active in another scope (the JVM and sessions share an id's series) keeps its series. Without a later event, the ended ids wait for one; they are never more than the scenarios that ended.

**OpenTelemetry** (`chaos-agent-opentelemetry`), the same instruments with attributes `chaos.event.type`, `chaos.scenario.id`, `chaos.operation`, `chaos.effect`, `chaos.skip.reason`, `chaos.failure.category`, `chaos.scenario.state`, scope `com.macstab.chaos.jvm`. `chaos.events` and `chaos.activation.failures` carry no scenario id; `chaos.applications`, `chaos.skips` and `chaos.injected.delay` keep `chaos.scenario.id`, because the API cannot retire a series: they are bounded by the SDK's cardinality limit, and a View drops the attribute where scenario ids are many:

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
| `ControlEnabled` (attribute) | yes          | Whether the operations below are allowed                            |
| `activatePlan(json)`      | no               | Activates a plan (startup-config JSON); returns the scenario ids    |
| `stopScenario(id)`        | no               | Stops a scenario activated through `activatePlan`                   |
| `stopAll()`               | no               | Stops every scenario activated through `activatePlan`               |

A plan that finished on its own (its `maxApplications` spent, or expired) is closed and forgotten at the next control operation: `stopAll()` does not count it.

The control operations are off by default — anyone who can reach the MBean server could otherwise inject faults. Enable them with the agent argument `jmxControl=true`, `-Dmacstab.chaos.jmx.control=true` or `MACSTAB_CHAOS_JMX_CONTROL=true` (any of `true|false|1|0|yes|no|on|off`, like every switch), and secure the JMX connector.

## 4a. Management endpoints

The same four operations over HTTP in each framework, all off by default and to be secured — anyone who reaches them can inject faults:

| Operation                      | Spring Boot (actuator)                                | Micronaut (management)                       | Quarkus (`chaos-agent-quarkus-management`) |
|--------------------------------|-------------------------------------------------------|----------------------------------------------|--------------------------------------------|
| Diagnostics snapshot           | `GET /actuator/chaos`                                 | `GET /chaos`                                 | `GET /q/chaos`                             |
| Activate a plan                | `POST /actuator/chaos`, body `{"planJson": "<plan>"}` | `POST /chaos`, body `{"planJson": "<plan>"}` | `POST /q/chaos`, body: the plan            |
| Stop a plan activated here     | `DELETE /actuator/chaos/{id}`                         | `DELETE /chaos/{id}`                         | `DELETE /q/chaos/{id}`                     |
| Stop every plan activated here | `DELETE /actuator/chaos`                              | `DELETE /chaos`                              | `DELETE /q/chaos`                          |
| Switch                         | `macstab.chaos.actuator.enabled=true`                 | `macstab.chaos.management.enabled=true` (needs `micronaut-management`) | add the extension; `macstab.chaos.management.enabled=true` at runtime |

`{id}` is a plan's handle id (`plan:<name>`, percent-encoded) or its name. It is percent-decoded as a path segment: a `+` is a plus, and a space is written `%20`. A stop of a plan that is still being activated answers 409 `conflict` and leaves the plan running once its activation completes; a stop-all during an activation stops that plan as soon as it is stored, and the activation answers `rejected`. Only plans activated through the endpoint can be stopped through it, and they are stopped when the application context (or Quarkus application, including a dev-mode reload) shuts down. All three share `ChaosManagement` (in the agent) and answer with JSON and the operation's status: 200 done; 400 `invalid` (malformed, failing validation); 404 `not-found`; 409 `conflict` (a scenario id is already active, a running plan has the plan's name, or a plan of that name is being activated, which also answers a stop; a plan without a name is named `default`, and so is a single scenario or an array of scenarios posted on its own: a second, different one answers 409 until the first is stopped) or `unmanaged` (a scenario not activated here); 422 `rejected` (the runtime could not run it, such as a stressor this JVM's flags leave without load, or a stop-all ran during its activation); 503 when no agent is installed — neither endpoint installs one. The Micronaut endpoint is `defaultSensitive`. The Quarkus route lives in its own extension, so only applications that add it depend on `quarkus-vertx-http`; it runs on a worker thread under Quarkus' non-application root (`/q` by default) and reads its switch on every request. With the Quarkus management interface enabled (`quarkus.management.enabled=true`) the route is served there instead, under `quarkus.management.root-path` (`/q` by default, port 9000), and the HTTP port answers 404: the management interface has its own authentication, so secure the route with `quarkus.management.auth.*` there. With an absolute `quarkus.http.non-application-root-path`, the route does not lie under `quarkus.http.root-path`; write the `quarkus.http.auth.permission` paths for its actual path. It answers 401 to a request without an authenticated user, that is without a non-anonymous Quarkus `SecurityIdentity` (configure Quarkus security for the path; with lazy authentication the deferred identity is resolved); `macstab.chaos.management.allow-anonymous=true` serves anonymous requests, for a network that is already restricted. A `POST` must be `application/json` (415 otherwise) and at most 1 MiB, the cap every plan has (413, decided from `Content-Length` before anything is read, or while reading a body without one); a negative or unparsable `Content-Length` is a 400. No body is read before the switch, the path and the caller admit the request; the body of a request refused early is discarded, and the connection of a body too large is closed.

## 5. Handle counters

`ChaosActivationHandle.matchedCount()` and `appliedCount()` return the scenario's counters (the sum over its scenarios for a plan handle), so a test can assert on the handle it activated:

```java
try (ChaosActivationHandle handle = chaos.activate(scenario)) {
    callTheService();
    assertThat(handle.appliedCount()).isPositive();
}
```

The diagnostics keep the 256 most recent activation failures (`snapshot().failures()`, also listed by `debugDump()`) and count the older ones in the runtime detail `droppedActivationFailures`. The `runtimeDetails()` also report `currentSessionId` (only while the calling thread is in a session), `wovenHookGroups` (see [`instrumentation.md`](instrumentation.md)), `droppedEvents`, and the hook health: `unmatchedHooks` lists the hooks woven into a type that matched none of its methods — the JDK or library changed the method, so the hook is inactive — and `hookFailures` the types whose weaving failed; both are empty on a healthy agent. A failed transformation is also logged as a warning.
