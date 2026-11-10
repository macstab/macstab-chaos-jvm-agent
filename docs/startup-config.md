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
            -Dmacstab.chaos.configJson       (system property)
Priority 2: env MACSTAB_CHAOS_CONFIG_JSON    (inline JSON in environment variable)
Priority 3: agent arg "configBase64"         (base64-encoded JSON in JVM argument)
            -Dmacstab.chaos.configBase64     (system property)
Priority 4: env MACSTAB_CHAOS_CONFIG_BASE64  (base64-encoded JSON in environment variable)
Priority 5: agent arg "configFile"           (file path in JVM argument)
            -Dmacstab.chaos.configFile       (system property)
Priority 6: env MACSTAB_CHAOS_CONFIG_FILE    (file path in environment variable)
```

Each system property is read after its agent argument and before its environment variable. System properties are launch-time local sources and are trusted as the agent arguments are.

If none of the above is present, `StartupConfigLoader.load()` returns `Optional.empty()`. The agent starts with no active scenarios.

---

# 3. Agent Argument Parsing

## Syntax

```
-javaagent:chaos-agent-bootstrap.jar=key1=value1;key2=value2;...
```

The raw argument string (the part after `=`) is parsed by `AgentArgsParser`:
- Split on `;` — commas are ordinary characters and need no escaping
- Escapes: `\;` is a literal `;`, `\=` a literal `=`, `\\` a literal `\`. Any other backslash is kept as written, so Windows paths (`configFile=C:\chaos\plan.json`) and the JSON escapes inside `configJson` (`\"`, `\n`, `\u0041`) reach the consumer unchanged. The JSON escape `\\` is the exception: it becomes a single `\`, so write a JSON backslash as `\\\\` in the argument (or use `configBase64` or `configFile`)
- Each token split on the first `=` into key and value
- Keys and values are trimmed of whitespace
- Duplicate keys: last value wins (a warning is printed to stderr)
- Keys without `=`: treated as a boolean flag set to `true` (e.g. `debugDumpOnStart`)
- Empty or null argument string: produces empty `AgentArgs`
- Arguments longer than 8192 characters are rejected

Boolean values accept `true|false|1|0|yes|no|on|off` (case-insensitive). `debugDump` is accepted as an alias of `debugDumpOnStart`; the system property `macstab.chaos.debugDumpOnStart` and the environment variable `MACSTAB_CHAOS_DEBUG_DUMP_ON_START` accept the same literals and are read in that order after the argument.

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

The agent arg takes precedence over the system property, which takes precedence over the environment variable; `eager` is case-insensitive. On demand, each hook group is woven the first time an activated scenario needs it, and a JVM without scenarios and without an open session runs unmodified JDK classes. `eager` weaves every group at start (the 1.x behaviour), so no activation pays the one-time weaving cost.

---

# 4. File Path Safety

When loading from a file path, `StartupConfigLoader.validateAndResolvePath()` applies:

1. **Normalize**: `Path.of(filePath).toAbsolutePath().normalize()` — resolves all `..` and `.` components, neutralizing directory traversal sequences before any check
2. **Existence check**: `Files.exists(path, LinkOption.NOFOLLOW_LINKS)` — `NOFOLLOW_LINKS` detects broken symlinks without following them
3. **Symlink rejection**: `Files.isSymbolicLink(path)` — symlinks are rejected, with one exception: the layout Kubernetes uses for ConfigMap and Secret volumes (`<dir>/plan.json` → `..data/plan.json`, `<dir>/..data` → a `..<timestamp>` directory beside it). There the real file inside the timestamped directory is checked (regular file, size, permissions) and read without following further links. Any other link is still rejected. This prevents attackers from redirecting config file reads to sensitive files via symlink manipulation after path validation
4. **Regular file check**: `Files.isRegularFile(path)` — rejects directories, pipes, devices
5. **Size limit**: size read with `NOFOLLOW_LINKS` `> 1_048_576` — rejects files larger than 1 MiB to prevent OOM from oversized configs
6. **Permissions** (file systems with a POSIX view only): a world-writable file, or a world-writable parent directory without the sticky bit, is rejected. A parent reached through a symbolic link (`/etc/chaos -> /srv/chaos`) is followed: its mode is read on the real directory (Linux reports every link as 0777). For a ConfigMap link, the volume directory holding `..data` is checked the same way, since whoever can write it can swap `..data`; it is accepted on a read-only mount, as Kubernetes mounts ConfigMap volumes (their directory mode is 0777). A mount that reports every file as 0777 (Azure Files, WSL `/mnt/c`, Docker Desktop bind mounts) is refused; the error says so: mount it with restrictive modes (`file_mode`/`dir_mode`, `umask`), or pass the plan inline with `configJson` / `configBase64`
7. **Owner** (file systems with a POSIX view only): the file, the ConfigMap link and a link to the file's directory must be owned by the JVM's user or by root, so another local user cannot plant a plan or redirect its directory. Skipped where the owner is unavailable or `user.name` is unknown (an unnamed container uid), and on file systems without a POSIX view (Windows: its owner names are `DOMAIN\user`; Windows is best-effort, Linux and macOS are supported)

**TOCTOU**: The checks above are not atomic with the read. The file is therefore opened with `LinkOption.NOFOLLOW_LINKS` (a path swapped for a symlink after validation fails to open), and at most 1 MiB + 1 byte is read, so a file that grows after the size check is rejected rather than loaded in full.

---

# 5. Base64 Encoding

The `configBase64` source accepts **standard or URL-safe Base64**. Whitespace (for example line breaks from wrapped `base64` output) is removed first; the input is then decoded with `Base64.getDecoder()` and, if that fails, with `Base64.getUrlDecoder()`. Any other invalid character is rejected — it is not skipped. Decoded bytes are interpreted as UTF-8.

Malformed base64 input throws `ConfigLoadException` with source `base64`.

---

# 6. JSON Plan Format

The format is specified in [plan-schema.md](plan-schema.md) and defined by the JSON Schema the agent publishes (`chaos-plan.schema.json`, generated from `ChaosPlanMapper` and checked against it by the build). The fields of every selector and effect are explained in [configuration-reference.md](configuration-reference.md). `ChaosPlanMapper` reads it strictly:

- Unknown fields and trailing content cause a `ConfigLoadException` (`FAIL_ON_UNKNOWN_PROPERTIES`, `FAIL_ON_TRAILING_TOKENS`): a misspelled field is an error, not a silently ignored setting.
- Durations are ISO-8601 strings (`"PT0.1S"` = 100 ms, `"PT30S"`, `"PT1M"`); instants are ISO-8601 UTC (`"2026-11-01T08:00:00Z"`).
- Enum constants are written exactly as declared (`"EXECUTOR_SUBMIT"`, `"SESSION"`); `"executor_submit"` is rejected.
- Name and path patterns are objects: `{"mode": "EXACT" | "PREFIX" | "GLOB" | "REGEX" | "ANY", "value": "..."}`, and for file paths `{"prefix": "/var/lib/app"}`.
- Every selector except `stress` requires a non-empty `operations` set, drawn from that selector's operations (the schema lists them per selector).

## A plan

```json
{
  "metadata": { "name": "checkout-soak", "description": "optional" },
  "observability": { "jmxEnabled": true, "structuredLoggingEnabled": true, "debugDumpOnStart": false },
  "scenarios": [
    {
      "id": "slow-pool-submit",
      "description": "optional",
      "scope": "JVM",
      "precedence": 0,
      "selector": {
        "type": "executor",
        "operations": ["EXECUTOR_SUBMIT"],
        "executorClassPattern": { "mode": "GLOB", "value": "*ThreadPool*" }
      },
      "effect": { "type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.5S" },
      "activationPolicy": {
        "probability": 0.5,
        "rateLimit": { "permits": 10, "window": "PT1S" },
        "activeFor": "PT30S",
        "maxApplications": 100,
        "randomSeed": 42
      }
    }
  ]
}
```

`metadata` may be omitted (the plan is then named `default`); when present, its `name` is required. `observability` may be omitted; its three fields default as shown. A file (or `configJson` / `configBase64`) may also hold a single scenario or a JSON array of scenarios, wrapped in a plan named `default` ([plan-schema](plan-schema.md)); a leading byte order mark is skipped.

## Activation policy defaults

Every policy field is optional:

- `startMode`: `AUTOMATIC` (`MANUAL` waits for `start()` on the handle)
- `probability`: `1.0`; must be in (0, 1]
- `rateLimit`: none; when present, `permits` > 0 and a positive `window` are required
- `activateAfterMatches`: `0`
- `activeFor`, `activeFrom`, `activeUntil`: no time window
- `maxApplications`: unlimited
- `randomSeed`: a random seed per activation; set it for reproducible draws
- `allowDestructiveEffects`: `false`; must be `true` for `deadlock` and `threadLeak`, which hold their threads, locks and memory until the scenario is stopped (without it, `virtualThreadCarrierPinning` leaves one carrier free, and is refused on a scheduler with a single carrier)
- `replayMatches`: none; when present, it replaces the probability draw and requires probability `1.0` and no `rateLimit`

---

# 7. Error Handling

`ConfigLoadException` carries:
- `message`: human-readable description
- `source`: identifies the source type (`"inline-json"`, `"base64"`, `"file:/path/to/file"`)
- `cause`: wrapped `IOException` or `JsonParseException` where applicable

JSON parse failures report line, column, the kind of failure and the JSON path, for example `failed to parse chaos plan JSON at line 7, column 21: unknown property 'delayy' at scenarios[0].effect`. The kinds are `malformed JSON`, `duplicate property`, `unknown property '<name>'`, `unknown or missing type`, `unknown value; expected one of <constants>` (an enum), `number out of range`, `missing or null value[: <name>]`, `invalid duration`, `invalid instant`, `value of the wrong type` and `invalid value: <validation message>`. A misspelled field is reported as the unknown property even where it also leaves a required field missing (`maxDelayy` is `unknown property 'maxDelayy' at scenarios[0].effect`, not the missing `maxDelay`); of several problems in one object, the first in document order is reported. The message never carries the parser's excerpt of the input. A validation message may quote the offending field value (`duplicate scenario id in plan: 'a'`); it is relayed sanitised (control and line-separator characters become U+FFFD) and capped at 200 characters. The cause (Jackson's own exception) can quote the input, so the agent never logs or prints it: the log sites write the sanitised message without the exception.

`ConfigLoadException` is an unchecked exception. It propagates out of `StartupConfigLoader.load()` and, if uncaught, aborts `premain()` — which typically causes the JVM to fail to start. `premain` rethrows it without its cause, so the JVM's report of the failed start does not print the input.

---

# 8. Observability

No logs are emitted by this module during normal operation. Errors throw `ConfigLoadException` with descriptive messages. The `debugDumpOnStart` agent argument causes `ChaosRuntime.diagnostics().debugDump()` to be printed to `System.err` immediately after plan activation — useful for startup verification.

A plan's `observability` block applies to that plan's scenarios for as long as the plan is active (until its handle is closed or, for a watched file, until a reload drops the scenario):

| Field                      | Default | `false` / `true` means                                                                                 |
|----------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `jmxEnabled`               | `true`  | `false`: the JMX `listScenarios` and `debugDump` operations leave the plan's scenarios out              |
| `structuredLoggingEnabled` | `true`  | `false`: the plan's events are not written to `java.util.logging` (listeners and metrics still get them) |
| `debugDumpOnStart`         | `false` | `true`: the diagnostics dump is printed to `System.err` once the plan is active (same as the agent argument) |

---

# 9. Live Config Reload — File Watch Mode

When the config source is a **file**, the agent can poll it continuously and apply incremental diffs to the live scenario registry. This is the primary integration point for external chaos pipeline frameworks that need to change the running scenario set without restarting the JVM.

## Enabling watch mode

```bash
# agent arg (milliseconds)
-javaagent:agent.jar=configFile=/etc/chaos/plan.json;configWatchInterval=500

# or via system properties
-Dmacstab.chaos.configFile=/etc/chaos/plan.json -Dmacstab.chaos.configWatchInterval=500

# or via environment variables
MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json
MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL=500
```

`configWatchInterval` (agent arg) takes precedence over `-Dmacstab.chaos.configWatchInterval`, then `MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL`, then its alias `MACSTAB_CHAOS_WATCH_INTERVAL` (env vars). A value that is not a number disables watching with one `[chaos-agent]` line naming it. A value of `0` or absent disables watching — the file is read once at startup (watching is off by default). Values below 50 ms are raised to 50 ms. Watch mode is only available for file sources; inline JSON and base64 sources always use read-once mode.

## Diff algorithm

On every poll tick `StartupConfigPoller`:

1. Resolves the file (following a Kubernetes ConfigMap link, see §4) and compares its stamp — the real file, modification time, size and file key — with the last read that was applied or refused for good. An unchanged stamp is a no-op: the file is not opened, so an unchanged file the JVM can no longer read (`chmod 000`, which changes only the change time) is not a failure. A `..data` link pointing at a directory that is gone counts as a missing file.
2. Otherwise reads it and compares a SHA-256 of the contents: an unchanged digest (a touch, a ConfigMap re-sync) only records the new stamp; contents that already failed to parse are not parsed again until they change.
3. A read within 20 ms of the file's modification time is provisional: the writer may still be writing, so every tick reads again even if the stamp did not change, until a read that began after the window (a second write in the same timestamp tick is not missed). A whole-second modification time gets one second, because a file system that stores whole seconds gives a same-size edit within that second the same stamp: a read at T+0.7 s, an edit at T+0.9 s and a tick at T+1.2 s apply the edit. The window counts from when the stamp was first seen at the latest, so a modification time ahead of the clock (a skewed file server) is read once more after the window, not on every tick.
4. If the file changed while it was being read, the tick is skipped and the next one retries.
5. Parses the new plan and computes a structural diff against the currently active scenario set:

| Case                                        | Action                     |
|---------------------------------------------|----------------------------|
| Same `id`, all 8 fields identical           | Kept running — untouched   |
| Same `id`, any field changed                | Stopped, then re-activated |
| Present in new plan, absent from active set | Activated                  |
| Present in active set, absent from new plan | Stopped                    |

Equality is record equality across all eight `ChaosScenario` fields. A single field change — even probability — triggers a stop + re-activate cycle.

**A removed file** stops every scenario it activated and removes the status file (with `reports=on`) once it has been missing on two ticks in a row — a save by rename, or `rm` then `cp`, does not reset the scenarios; when the file comes back it is loaded as a change. Malformed or invalid content, and a plan whose hooks cannot be woven, is reported and not tried again until the file changes; an id taken by a scenario activated elsewhere may clear and is retried on every tick, but logged and reported once. A new or changed id taken elsewhere (also an id the reloader's own scenario held until it ended) refuses the reload before any scenario stops, so the running ones keep their counters, budgets and windows. The plan's `observability` settings are applied after these checks and before its scenarios start, so `structuredLoggingEnabled=false` mutes their first events too. An error in a tick (an `OutOfMemoryError`, a `StackOverflowError`) is logged and the watching goes on; the next tick tries again. The reloader's baseline is the very read the agent started with, so a change made during startup is applied on the first tick. A plan file (and a ConfigMap link) owned by another user than the JVM's, root excepted, is refused. A file that cannot be read or parsed keeps the current plan and is reported in the status file (see [`observability.md`](observability.md)).

The reload is applied atomically. Every new or changed scenario is validated, and its hooks woven (method selectors included), first; an invalid one, one the runtime cannot run, one whose hooks cannot be woven, or duplicate ids reject the whole plan before anything changes, so the running scenarios keep running without a gap. Each such refusal is recorded once in the diagnostics and published as a `FAILED` event (the same content is not retried until the file changes). An id taken by a scenario activated elsewhere also rejects the plan before anything changes, but is not recorded: it may clear, so it is retried on every tick (stderr and the status file report it once). Then removed and changed scenarios are stopped and every new or changed scenario is activated. If an activation still fails, the scenarios activated by this reload are removed again and the previous versions are restored. Every failure is logged to stderr and, with `reports=on`, reported in the status file, and the next file change is retried. A file that fails to parse leaves the active set untouched. `ConfigReloadTortureTest` checks these rules under concurrent ConfigMap swaps, in-place rewrites and invalid plans.

## Isolation guarantee

The poller only manages scenarios it activated itself (from the config file). Scenarios activated programmatically via `ChaosRuntime.activate()` or through a `ChaosSession` are invisible to the poller and are never stopped or modified by a reload.

## Implementation details

- Single daemon thread named `chaos-config-poller`; ticks run with a fixed delay between them, so a slow read is not followed by ticks back to back.
- The poller's own stat, read and re-check of the file are agent work: `FILE_IO` scenarios do not match them, so a `FILE_IO_READ` reject does not fail a reload.
- Scheduler started by `startWithInitialPlan()` during agent initialization; stopped by `close()`.
- `close()` stops the scheduler and stops and unregisters all managed scenarios.
- The daemon thread does not prevent JVM shutdown.

## External framework integration

A chaos pipeline framework that wants to push a new scenario set into a running JVM:

1. Write the updated plan to a temp file in the same directory.
2. Atomically rename it over the watched file (`mv` / `Files.move` with `ATOMIC_MOVE`).
3. Wait one poll interval — the agent detects the change, diffs, and applies; with `reports=on`, `<tmpdir>/.chaos-jvm.status` reports the result (`state=ok` or the parse error's line and column).

On Kubernetes, mount the plan from a ConfigMap and point `configFile` at the mounted file: updates of the ConfigMap (an atomic swap of the volume's `..data` link) are picked up like any other change.

No JVM restart required.

## Configuration reference

| Agent arg             | Environment variable           | Type      | Default      | Description                  |
|-----------------------|--------------------------------|-----------|--------------|------------------------------|
| `configWatchInterval` (`-Dmacstab.chaos.configWatchInterval`) | `MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL` (alias `MACSTAB_CHAOS_WATCH_INTERVAL`) | long (ms) | 0 (disabled) | Poll interval; 0 = read-once; minimum 50 |

---

# 10. References

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
