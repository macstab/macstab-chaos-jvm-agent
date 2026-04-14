# 1. Overview

## Purpose

`chaos-agent-startup-config` resolves and parses startup-time chaos configuration before the application begins normal execution. It converts agent args, environment variables, inline JSON, base64 payloads, or files into a `ChaosPlan`.

## Scope

In scope:

- agent arg parsing
- precedence resolution
- JSON mapping
- startup debug dump flag resolution

Out of scope:

- runtime validation beyond deserialization
- steady-state configuration reload
- remote config fetch

# 2. Architectural Context

This module is called by bootstrap during initialization. It depends on:

- `chaos-agent-api`
- Jackson databind
- Jackson JSR-310 module

It is intentionally small and init-time oriented.

# 3. Key Concepts And Terminology

- Agent args: the raw string passed after `-javaagent:jar=...`
- Inline JSON: JSON carried directly in `configJson`
- Base64 JSON: JSON carried in `configBase64`
- Loaded plan: the parsed `ChaosPlan` plus source and debug-dump metadata

# 4. End-to-End Behavior

Resolution algorithm:

1. Parse raw agent args into `key=value` tokens.
2. Resolve `configJson` from agent args first, then environment.
3. Resolve `configBase64` from agent args first, then environment.
4. Resolve `configFile` from agent args first, then environment.
5. Resolve `debugDumpOnStart` as agent arg OR environment flag.
6. Load the first available config source in the order inline JSON, base64 JSON, file.
7. Deserialize into `ChaosPlan`.

Important consequence:

- source precedence is hard-coded and mutually exclusive; if inline JSON is present, file config is ignored even if also present

# 5. Architecture Diagrams

No PlantUML diagram is included here. The flow is linear, fully local, and short enough that a precedence table is more precise than a diagram.

# 6. Component Breakdown

## `AgentArgsParser`

Responsibility:

- split `key=value` pairs on `;`
- support backslash escaping

Design trade-off:

- simple and deterministic
- not a shell-like quoting language

## `StartupConfigLoader`

Responsibility:

- resolve config precedence across args and environment
- decode base64 when needed
- read config files when needed
- expose `LoadedPlan`

## `ChaosPlanMapper`

Responsibility:

- map JSON to and from `ChaosPlan`

# 7. Data Model And State

## Resolution Precedence

Current precedence:

1. `configJson` from agent args, else `MACSTAB_CHAOS_CONFIG_JSON`
2. `configBase64` from agent args, else `MACSTAB_CHAOS_CONFIG_BASE64`
3. `configFile` from agent args, else `MACSTAB_CHAOS_CONFIG_FILE`

For the same key, agent args win over environment variables.

## Debug Dump Flag

Current precedence behavior:

- `debugDumpOnStart=true` in agent args enables it
- `MACSTAB_CHAOS_DEBUG_DUMP_ON_START=true` also enables it
- the two are OR-ed rather than chosen by precedence

Important caveat:

- `ChaosPlan.Observability.debugDumpOnStart` is not what controls startup dumping in the current bootstrap path

## Parser Grammar

The parser expects:

- tokens separated by `;`
- each token formatted as `key=value`

Important caveats:

- invalid tokens throw immediately
- this is not quoted shell syntax
- inline JSON in agent args is operationally awkward because shell escaping and Java agent argument parsing interact poorly

For production use, `configFile` or `configBase64` is typically safer than raw inline JSON.

## JSON Mapping Behavior

`ChaosPlanMapper`:

- registers `JavaTimeModule`
- disables trailing-token failures
- enables unknown-property failures

Operational consequence:

- config is strict about field names
- extra keys are rejected
- trailing tokens after the main JSON document are tolerated

# 8. Concurrency And Threading Model

This module is effectively single-threaded init code. It has no meaningful concurrent shared state of its own.

# 9. Error Handling And Failure Modes

Expected failures:

- malformed agent arg token
- invalid base64 payload
- unreadable config file
- invalid JSON
- unknown JSON properties

Failure behavior:

- file read failures are wrapped in `IllegalArgumentException`
- JSON parse errors are wrapped in `IllegalArgumentException`
- there is no fallback from a chosen source to a lower-precedence source if the chosen source is present but invalid

That last point matters operationally. A broken `configJson` value blocks loading even if a valid file path is also present.

# 10. Security Model

Configuration is treated as trusted operator input.

Security-relevant implications:

- config directly controls exception injection, blocking, and resource stress behavior
- no secret management or encryption model is present
- inline JSON and environment transport may expose config in process listings or environment inspection depending on deployment practices

# 11. Performance Model

All cost is startup-only:

- string parsing
- optional base64 decoding
- optional file read
- JSON parse and object allocation

This module is not on the steady-state hot path.

# 12. Observability And Operations

This module exposes source provenance only through `LoadedPlan.source`, which bootstrap currently does not surface to operators after initialization.

Operational guidance:

- prefer `configFile` or `configBase64` for reproducibility
- enable `debugDumpOnStart` when verifying startup activation, not as a permanent observability strategy

# 13. Configuration Reference

Supported keys:

- `configJson`
- `configBase64`
- `configFile`
- `debugDumpOnStart`

Supported environment variables:

- `MACSTAB_CHAOS_CONFIG_JSON`
- `MACSTAB_CHAOS_CONFIG_BASE64`
- `MACSTAB_CHAOS_CONFIG_FILE`
- `MACSTAB_CHAOS_DEBUG_DUMP_ON_START`

Example:

```text
configFile=/opt/app/chaos-plan.json;debugDumpOnStart=true
```

Example plan shape:

```json
{
  "metadata": {
    "name": "startup-delay",
    "description": "Delay executor submissions at JVM startup"
  },
  "observability": {
    "jmxEnabled": true,
    "structuredLoggingEnabled": true,
    "debugDumpOnStart": false
  },
  "scenarios": [
    {
      "id": "delay-submit",
      "scope": "JVM",
      "selector": {
        "type": "executor",
        "operations": ["EXECUTOR_SUBMIT"],
        "executorClassPattern": {
          "mode": "EXACT",
          "value": "java.util.concurrent.ThreadPoolExecutor"
        },
        "taskClassPattern": {
          "mode": "ANY",
          "value": "*"
        },
        "scheduledOnly": null
      },
      "effect": {
        "type": "delay",
        "minDelay": "PT0.075S",
        "maxDelay": "PT0.075S"
      },
      "activationPolicy": {
        "startMode": "AUTOMATIC",
        "probability": 1.0,
        "activateAfterMatches": 0,
        "maxApplications": null,
        "activeFor": null,
        "rateLimit": null,
        "randomSeed": 0
      },
      "precedence": 0,
      "tags": {}
    }
  ]
}
```

# 14. Extension Points And Compatibility Guarantees

Treat this module as internal bootstrap support code. The stable compatibility point is the serialized shape of `ChaosPlan` and related API records, not the exact precedence algorithm or helper class names.

# 15. Stack Walkdown

## API Layer

Relevant because this module materializes serialized data into API records.

## Application / Runtime Layer

Relevant only at startup; once the plan is loaded, runtime semantics move to the core.

## JVM Layer

Materially relevant only because agent args and environment are part of JVM process startup context.

## Memory / Concurrency Layer

Not materially relevant. This is init-time parsing code.

## OS / Container Layer

Relevant for file path visibility, environment injection, and the security posture of environment variables and process arguments.

## Infrastructure Layer

Relevant where external deployment tooling or orchestration systems inject config values.

# 16. References

- Reference: Java Platform SE API Specification — `java.util.Base64`
- Reference: Java Platform SE API Specification — `java.nio.file`
