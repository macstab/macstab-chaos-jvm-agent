<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-startup-config — Startup Configuration Reference

> Internal reference for configuration source resolution, argument parsing, path safety, and JSON plan mapping.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`chaos-agent-startup-config` resolves a `ChaosPlan` from externally supplied configuration at agent startup. It handles three source types (inline JSON, base64-encoded JSON, file path), applies priority ordering, performs path safety checks for file sources, and delegates JSON deserialization to `ChaosPlanMapper` (Jackson).

## Scope

In scope:
- `StartupConfigLoader` — source resolution and loading
- `AgentArgsParser` — semicolon-separated key=value agent argument parsing
- `AgentArgs` — parsed argument accessor
- `ChaosPlanMapper` — Jackson ObjectMapper configuration; `ChaosPlan` serialization/deserialization
- `ConfigLoadException` — failure type with source metadata

Out of scope:
- Runtime scenario activation (delegated to `ChaosRuntime`)
- Agent argument validation beyond parsing

---

# 2. Configuration Source Priority

Sources are evaluated in fixed priority order. **First match wins.** No merging.

```
Priority 1: agent arg "configJson"           (inline JSON string in JVM argument)
Priority 2: env MACSTAB_CHAOS_CONFIG_JSON    (inline JSON in environment variable)
Priority 3: agent arg "configBase64"         (base64-encoded JSON in JVM argument)
Priority 4: env MACSTAB_CHAOS_CONFIG_BASE64  (base64-encoded JSON in environment variable)
Priority 5: agent arg "configFile"           (file path in JVM argument)
Priority 6: env MACSTAB_CHAOS_CONFIG_FILE    (file path in environment variable)
```

If none of the above is present, `StartupConfigLoader.load()` returns `Optional.empty()`. The agent starts with no active scenarios.

---

# 3. Agent Argument Parsing

## Syntax

```
-javaagent:chaos-agent-bootstrap.jar=key1=value1;key2=value2;...
```

The raw argument string (the part after `=`) is parsed by `AgentArgsParser`:
- Split on `;` — commas are ordinary characters and need no escaping
- Escapes: `\;` is a literal `;`, `\=` a literal `=`, `\\` a literal `\`. Any other backslash is kept as written, so Windows paths (`configFile=C:\chaos\plan.json`) and JSON escapes inside `configJson` reach the consumer unchanged
- Each token split on the first `=` into key and value
- Keys and values are trimmed of whitespace
- Duplicate keys: last value wins (a warning is printed to stderr)
- Keys without `=`: treated as a boolean flag set to `true` (e.g. `debugDumpOnStart`)
- Empty or null argument string: produces empty `AgentArgs`
- Arguments longer than 8192 characters are rejected

Boolean values accept `true|false|1|0|yes|no|on|off` (case-insensitive). `debugDump` is accepted as an alias of `debugDumpOnStart`; the environment variable `MACSTAB_CHAOS_DEBUG_DUMP_ON_START` accepts the same literals.

## AgentArgs Access

```java
agentArgs.get("configFile")           // String or null
agentArgs.getBoolean("debugDumpOnStart", false)  // boolean with default
```

## Instrumentation Mode

The `instrumentation` key is parsed by `AgentArgsParser` but read by `chaos-agent-bootstrap`, not by the plan loader. It selects when the agent weaves its hooks:

| Source                 | Key                              | Values                                                     |
|------------------------|----------------------------------|------------------------------------------------------------|
| Agent arg              | `instrumentation`                | `eager`; anything else or absent = on demand (the default) |
| System property        | `macstab.chaos.instrumentation`  | same                                                       |
| Environment variable   | `MACSTAB_CHAOS_INSTRUMENTATION`  | same                                                       |

The agent arg takes precedence over the system property, which takes precedence over the environment variable; `eager` is case-insensitive. On demand, each hook group is woven the first time an activated scenario needs it, and a JVM without scenarios runs unmodified JDK classes. `eager` weaves every group at start (the 1.x behaviour), so no activation pays the one-time weaving cost.

---

# 4. File Path Safety

When loading from a file path, `StartupConfigLoader.validateAndResolvePath()` applies:

1. **Normalize**: `Path.of(filePath).toAbsolutePath().normalize()` — resolves all `..` and `.` components, neutralizing directory traversal sequences before any check
2. **Existence check**: `Files.exists(path, LinkOption.NOFOLLOW_LINKS)` — `NOFOLLOW_LINKS` detects broken symlinks without following them
3. **Symlink rejection**: `Files.isSymbolicLink(path)` — symlinks are rejected, with one exception: the layout Kubernetes uses for ConfigMap and Secret volumes (`<dir>/plan.json` → `..data/plan.json`, `<dir>/..data` → a `..<timestamp>` directory beside it). There the real file inside the timestamped directory is checked (regular file, size, permissions) and read without following further links. Any other link is still rejected. This prevents attackers from redirecting config file reads to sensitive files via symlink manipulation after path validation
4. **Regular file check**: `Files.isRegularFile(path)` — rejects directories, pipes, devices
5. **Size limit**: size read with `NOFOLLOW_LINKS` `> 1_048_576` — rejects files larger than 1 MiB to prevent OOM from oversized configs
6. **Permissions** (POSIX filesystems only): a world-writable file, or a world-writable parent directory without the sticky bit, is rejected

**TOCTOU**: The checks above are not atomic with the read. The file is therefore opened with `LinkOption.NOFOLLOW_LINKS` (a path swapped for a symlink after validation fails to open), and at most 1 MiB + 1 byte is read, so a file that grows after the size check is rejected rather than loaded in full.

---

# 5. Base64 Encoding

The `configBase64` source accepts **standard or URL-safe Base64**. Whitespace (for example line breaks from wrapped `base64` output) is removed first; the input is then decoded with `Base64.getDecoder()` and, if that fails, with `Base64.getUrlDecoder()`. Any other invalid character is rejected — it is not skipped. Decoded bytes are interpreted as UTF-8.

Malformed base64 input throws `ConfigLoadException` with category `base64`.

---

# 6. JSON Plan Format

`ChaosPlanMapper` uses Jackson with:
- `FAIL_ON_UNKNOWN_PROPERTIES = true`: unknown fields in the JSON cause a `ConfigLoadException`. This prevents silent partial configuration from misconfigured JSON.
- Duration fields: ISO-8601 strings via Jackson's JavaTimeModule (e.g., `"PT0.1S"` = 100 ms, `"PT30S"` = 30 seconds, `"PT1M"` = 1 minute)
- Enums: case-insensitive string matching

## Full Plan Schema

```json
{
  "metadata": {                       // optional; default name "default"
    "name": "string",
    "description": "string (optional)"
  },
  "observability": { ... },           // optional; parsed but not read by the runtime
  "scenarios": [
    {
      "id": "string (required, unique per plan)",
      "description": "string (optional)",
      "scope": "JVM | SESSION",
      "precedence": 0,
      "selector": { ...selector object... },
      "effect": { ...effect object... },
      "activationPolicy": { ...policy object... }
    }
  ]
}
```

## Selector Object Formats

```json
{ "type": "executor",
  "operations": ["EXECUTOR_SUBMIT"],          // optional; defaults to all executor ops
  "executorClassPattern": ".*ThreadPool.*",   // optional; null = wildcard
  "taskClassPattern": null }

{ "type": "thread",
  "operations": ["THREAD_START"],
  "threadNamePattern": "worker-.*",
  "kind": "ANY | PLATFORM | VIRTUAL",
  "daemon": true }                            // optional; null = wildcard

{ "type": "network",
  "operations": ["SOCKET_CONNECT", "SOCKET_READ", "SOCKET_WRITE"],
  "remoteHostPattern": "db.internal.*" }

{ "type": "method",
  "operations": ["METHOD_ENTER"],
  "classPattern": "com.example.MyService",
  "methodNamePattern": "processOrder",
  "signaturePattern": null }                  // optional; matches the JVM descriptor, e.g. "(Ljava/lang/String;)V"

{ "type": "jvmRuntime",
  "operations": ["SYSTEM_CLOCK_MILLIS", "SYSTEM_CLOCK_NANOS"] }

{ "type": "stress",
  "target": "HEAP | THREADS | GC | METASPACE | DIRECT_BUFFER | FINALIZER |
             DEADLOCK | THREAD_LEAK | THREAD_LOCAL_LEAK | MONITOR_CONTENTION |
             CODE_CACHE | SAFEPOINT | STRING_INTERN | REFERENCE_QUEUE" }
```

## Effect Object Formats

```json
{ "type": "delay",
  "minDelay": "PT0.1S",       // ISO-8601 duration
  "maxDelay": "PT0.5S" }      // equal to minDelay for fixed delay

{ "type": "reject",
  "message": "synthetic failure" }

{ "type": "suppress" }

{ "type": "gate",
  "maxBlock": "PT30S" }       // null = block indefinitely until release()

{ "type": "exceptionalCompletion",
  "failureKind": "TIMEOUT | CANCELLATION | EXECUTION | IO",
  "message": "simulated timeout" }

{ "type": "exceptionInjection",
  "exceptionClassName": "java.io.IOException",
  "message": "chaos injected",
  "withStackTrace": true }

{ "type": "returnValueCorruption",
  "strategy": "NULL | ZERO | EMPTY | BOUNDARY_MAX | BOUNDARY_MIN" }

{ "type": "clockSkew",
  "mode": "FIXED | DRIFT | FREEZE",
  "offsetMillis": 5000 }      // irrelevant for FREEZE

{ "type": "spuriousWakeup" }

{ "type": "heapPressure",
  "bytes": 67108864 }         // 64 MiB

{ "type": "keepAlive",
  "threads": 4 }

{ "type": "metaspacePressure",
  "classCount": 200 }

{ "type": "directBufferPressure",
  "bytes": 33554432 }         // 32 MiB

{ "type": "gcPressure",
  "allocationRatePerSecond": 104857600 }  // 100 MiB/s

{ "type": "finalizerBacklog",
  "objectCount": 1000 }

{ "type": "deadlock" }

{ "type": "threadLeak",
  "count": 5 }

{ "type": "threadLocalLeak",
  "entryCount": 50 }

{ "type": "monitorContention",
  "threads": 8 }

{ "type": "codeCachePressure",
  "classCount": 500 }

{ "type": "safepointStorm",
  "intervalMillis": 100 }

{ "type": "stringInternPressure",
  "count": 100000 }

{ "type": "referenceQueueFlood",
  "count": 50000 }
```

## Activation Policy Object Format

```json
{
  "startMode": "AUTOMATIC | MANUAL",
  "probability": 1.0,
  "rateLimit": {
    "permits": 10,
    "window": "PT1S"
  },
  "activateAfterMatches": 0,
  "activeFor": "PT30S",
  "maxApplications": 100,
  "randomSeed": 42
}
```

All fields are optional. Absent fields use defaults:
- `startMode`: `AUTOMATIC`
- `probability`: `1.0`
- `rateLimit`: null (no limit)
- `activateAfterMatches`: `0`
- `activeFor`: null (no expiry)
- `maxApplications`: null (unlimited)
- `randomSeed`: null (equivalent to `0`)

---

# 7. Error Handling

`ConfigLoadException` carries:
- `message`: human-readable description
- `source`: identifies the source type (`"inline-json"`, `"base64"`, `"file:/path/to/file"`)
- `cause`: wrapped `IOException` or `JsonParseException` where applicable

JSON parse failures report line, column, the kind of failure and the JSON path, for example `failed to parse chaos plan JSON at line 7, column 21: unknown property 'delayy' at scenarios[0].effect`. Values from the input are never included in the message, only field names.

`ConfigLoadException` is an unchecked exception. It propagates out of `StartupConfigLoader.load()` and, if uncaught, aborts `premain()` — which typically causes the JVM to fail to start.

---

# 8. Observability

No logs are emitted by this module during normal operation. Errors throw `ConfigLoadException` with descriptive messages. The `debugDumpOnStart` flag causes `ChaosRuntime.diagnostics().debugDump()` to be printed to `System.err` immediately after plan activation — useful for startup verification.

---

# 10. Live Config Reload — File Watch Mode

When the config source is a **file**, the agent can poll it continuously and apply incremental diffs to the live scenario registry. This is the primary integration point for external chaos pipeline frameworks that need to change the running scenario set without restarting the JVM.

## Enabling watch mode

```bash
# agent arg (milliseconds)
-javaagent:agent.jar=configFile=/etc/chaos/plan.json;configWatchInterval=500

# or via environment variables
MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json
MACSTAB_CHAOS_WATCH_INTERVAL=500
```

`configWatchInterval` (agent arg) takes precedence over `MACSTAB_CHAOS_WATCH_INTERVAL` (env var). A value of `0` or absent disables watching — the file is read once at startup (watching is off by default). Values below 50 ms are raised to 50 ms. Watch mode is only available for file sources; inline JSON and base64 sources always use read-once mode.

## Diff algorithm

On every poll tick `StartupConfigPoller`:

1. Resolves the file (following a Kubernetes ConfigMap link, see §3) and compares its stamp — the real file, modification time, size and file key — with the last successful read. An unchanged stamp is a no-op.
2. Otherwise reads it and compares a SHA-256 of the contents: an unchanged digest (a touch, a ConfigMap re-sync) only records the new stamp; contents that already failed to parse are not parsed again until they change.
3. A read within 20 ms of the file's modification time is provisional: the writer may still be writing, so the next tick reads again even if the stamp did not change (a second write in the same timestamp tick is not missed).
4. If the file changed while it was being read, the tick is skipped and the next one retries.
5. Parses the new plan and computes a structural diff against the currently active scenario set:

| Case                                        | Action                     |
|---------------------------------------------|----------------------------|
| Same `id`, all 8 fields identical           | Kept running — untouched   |
| Same `id`, any field changed                | Stopped, then re-activated |
| Present in new plan, absent from active set | Activated                  |
| Present in active set, absent from new plan | Stopped                    |

Equality is record equality across all eight `ChaosScenario` fields. A single field change — even probability — triggers a stop + re-activate cycle.

**A removed file** stops every scenario it activated and removes the status file; when the file comes back it is loaded as a change. A file that cannot be read or parsed keeps the current plan and is reported in the status file (see [`observability.md`](observability.md)).

The reload is applied atomically: removed and changed scenarios are stopped first, then every new or changed scenario is activated. If any activation fails (or the plan contains duplicate ids), the scenarios activated by this reload are removed again and the previous versions are restored; the failure is logged to stderr and the next file change is retried. A file that fails to parse leaves the active set untouched.

## Isolation guarantee

The poller only manages scenarios it activated itself (from the config file). Scenarios activated programmatically via `ChaosRuntime.activate()` or through a `ChaosSession` are invisible to the poller and are never stopped or modified by a reload.

## Implementation details

- Single daemon thread named `chaos-config-poller`.
- Scheduler started by `startWithInitialPlan()` during agent initialization; stopped by `close()`.
- `close()` stops the scheduler and stops and unregisters all managed scenarios.
- The daemon thread does not prevent JVM shutdown.

## External framework integration

A chaos pipeline framework that wants to push a new scenario set into a running JVM:

1. Write the updated plan to a temp file in the same directory.
2. Atomically rename it over the watched file (`mv` / `Files.move` with `ATOMIC_MOVE`).
3. Wait one poll interval — the agent detects the change, diffs, and applies; `<tmpdir>/.chaos-jvm.status` reports the result (`state=ok` or the parse error's line and column).

On Kubernetes, mount the plan from a ConfigMap and point `configFile` at the mounted file: updates of the ConfigMap (an atomic swap of the volume's `..data` link) are picked up like any other change.

No JVM restart required.

## Configuration reference

| Agent arg             | Environment variable           | Type      | Default      | Description                  |
|-----------------------|--------------------------------|-----------|--------------|------------------------------|
| `configWatchInterval` | `MACSTAB_CHAOS_WATCH_INTERVAL` | long (ms) | 0 (disabled) | Poll interval; 0 = read-once; minimum 50 |

---

# 9. References

- Reference: ISO 8601 — Date and time format; duration strings (`PT0.1S`, `PT30S`) — https://www.iso.org/iso-8601-date-and-time-format.html
- Reference: Jackson `ObjectMapper` — `FAIL_ON_UNKNOWN_PROPERTIES`, `JavaTimeModule` for `Duration` — https://github.com/FasterXML/jackson-databind
- Reference: Java SE API — `java.util.Base64.Decoder` (standard Base64 alphabet, RFC 4648 §4) — https://docs.oracle.com/en/java/docs/api/java.base/java/util/Base64.html
- Reference: RFC 4648 §4 — Base64 Encoding — https://www.rfc-editor.org/rfc/rfc4648#section-4
- Reference: Java SE API — `java.nio.file.Files.exists(Path, LinkOption...)`, `LinkOption.NOFOLLOW_LINKS` — https://docs.oracle.com/en/java/docs/api/java.base/java/nio/file/Files.html
- Reference: Java SE API — `java.nio.file.Path.normalize()` (resolves `..` path traversal) — https://docs.oracle.com/en/java/docs/api/java.base/java/nio/file/Path.html#normalize()
- Reference: CWE-22 — Path Traversal — https://cwe.mitre.org/data/definitions/22.html
- Reference: CWE-61 — UNIX Symbolic Link Following — https://cwe.mitre.org/data/definitions/61.html

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
