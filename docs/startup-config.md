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
- `AgentArgsParser` — comma-separated key=value agent argument parsing
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
-javaagent:chaos-agent-bootstrap.jar=key1=value1,key2=value2,...
```

The raw argument string (the part after `=`) is parsed by `AgentArgsParser`:
- Split on `,` (but `\,` is an escaped comma — treated as literal `,` within a value)
- Each token split on the first `=` into key and value
- Keys and values are trimmed of whitespace
- Duplicate keys: last value wins
- Keys without `=`: treated as a boolean flag set to `true`
- Empty or null argument string: produces empty `AgentArgs`

## AgentArgs Access

```java
agentArgs.get("configFile")           // String or null
agentArgs.getBoolean("debugDump", false)  // boolean with default
```

---

# 4. File Path Safety

When loading from a file path, `StartupConfigLoader.validateAndResolvePath()` applies:

1. **Normalize**: `Path.of(filePath).toAbsolutePath().normalize()` — resolves all `..` and `.` components, neutralizing directory traversal sequences before any check
2. **Existence check**: `Files.exists(path, LinkOption.NOFOLLOW_LINKS)` — `NOFOLLOW_LINKS` detects broken symlinks without following them
3. **Symlink rejection**: `Files.isSymbolicLink(path)` — symlinks are rejected outright. This prevents attackers from redirecting config file reads to sensitive files via symlink manipulation after path validation
4. **Regular file check**: `Files.isRegularFile(path)` — rejects directories, pipes, devices
5. **Size limit**: `Files.size(path) > 1_048_576` — rejects files larger than 1 MiB to prevent OOM from oversized configs

**TOCTOU risk**: The `exists` + `isSymbolicLink` + `isRegularFile` + `size` sequence is not atomic. A race between the checks and `Files.readString()` is theoretically possible on adversarial filesystems. This risk is accepted because the agent operates in a trusted environment; the size limit is the primary OOM protection.

---

# 5. Base64 Encoding

The `configBase64` source expects **standard Base64** (not URL-safe Base64). `Base64.getDecoder()` is used, not `Base64.getUrlDecoder()`. Decoded bytes are interpreted as UTF-8.

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
  "name": "string (required)",
  "metadata": {
    "description": "string (optional)",
    "tags": ["string"] 
  },
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
  "signaturePattern": null }                  // optional; matches parameter type string

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

`ConfigLoadException` is an unchecked exception. It propagates out of `StartupConfigLoader.load()` and, if uncaught, aborts `premain()` — which typically causes the JVM to fail to start.

---

# 8. Observability

No logs are emitted by this module during normal operation. Errors throw `ConfigLoadException` with descriptive messages. The `debugDumpOnStart` flag causes `ChaosRuntime.diagnostics().debugDump()` to be printed to `System.out` immediately after plan activation — useful for startup verification.

---

# 10. Live Config Reload — File Watch Mode

When the config source is a **file**, the agent can poll it continuously and apply incremental diffs to the live scenario registry. This is the primary integration point for external chaos pipeline frameworks that need to change the running scenario set without restarting the JVM.

## Enabling watch mode

```bash
# agent arg (milliseconds)
-javaagent:agent.jar=configFile=/etc/chaos/plan.json,configWatchInterval=500

# or via environment variables
MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json
MACSTAB_CHAOS_WATCH_INTERVAL=500
```

`configWatchInterval` (agent arg) takes precedence over `MACSTAB_CHAOS_WATCH_INTERVAL` (env var). A value of `0` or absent disables watching — the file is read once at startup. Watch mode is only available for file sources; inline JSON and base64 sources always use read-once mode.

## Diff algorithm

On every poll tick `StartupConfigPoller`:

1. Stats the file (`Files.getLastModifiedTime`). If the mtime matches the last successful read, the tick is a no-op — no parse, no diff.
2. If the mtime changed: reads and parses the new plan.
3. Computes a structural diff against the currently active scenario set:

| Case                                        | Action                     |
|---------------------------------------------|----------------------------|
| Same `id`, all 8 fields identical           | Kept running — untouched   |
| Same `id`, any field changed                | Stopped, then re-activated |
| Present in new plan, absent from active set | Activated                  |
| Present in active set, absent from new plan | Stopped                    |

Equality is record equality across all eight `ChaosScenario` fields. A single field change — even probability — triggers a stop + re-activate cycle.

## Isolation guarantee

The poller only manages scenarios it activated itself (from the config file). Scenarios activated programmatically via `ChaosRuntime.activate()` or through a `ChaosSession` are invisible to the poller and are never stopped or modified by a reload.

## Implementation details

- Single daemon thread named `chaos-config-poller`.
- Scheduler started by `startWithInitialPlan()` during agent initialization; stopped by `close()`.
- `close()` stops the scheduler and calls `ChaosActivationHandle.stop()` on all managed scenarios.
- The daemon thread does not prevent JVM shutdown.

## External framework integration

A chaos pipeline framework that wants to push a new scenario set into a running JVM:

1. Write the updated plan to a temp file in the same directory.
2. Atomically rename it over the watched file (`mv` / `Files.move` with `ATOMIC_MOVE`).
3. Wait one poll interval — the agent detects the mtime change, diffs, and applies.

No JVM restart required.

## Configuration reference

| Agent arg             | Environment variable           | Type      | Default      | Description                  |
|-----------------------|--------------------------------|-----------|--------------|------------------------------|
| `configWatchInterval` | `MACSTAB_CHAOS_WATCH_INTERVAL` | long (ms) | 0 (disabled) | Poll interval; 0 = read-once |

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
