<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# The Plan Format — Specification

> The exact syntax of a chaos plan: what a plan file, a `@WithChaos` annotation, the management endpoints and JMX `activatePlan` accept. The meaning of each part — how a selector matches, how a policy decides, how effects compose — is in [semantics.md](semantics.md); the fields of each selector and effect are explained in [configuration-reference.md](configuration-reference.md).
>
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

## 1. The schema is the definition

The format is defined by a JSON Schema (draft 2020-12), published in the agent at

```text
chaos-agent-startup-config.jar!/com/macstab/chaos/jvm/startup/chaos-plan.schema.json
```

and identified as `https://macstab.com/schemas/chaos-plan.schema.json`. Point an editor at it to get completion and validation while writing a plan.

The schema is not written by hand: `PlanSchemaTest` derives it from the plan mapper (`ChaosPlanMapper`) — the record components and creators the mapper binds, the `type` names of selectors and effects, its rejection of unknown fields — and fails the build when the committed file differs. Its constraints are probed, not listed: a property is `required`, an array has `minItems: 1` and a string the non-blank `pattern: "\\S"` only if the mapper rejects the violation in every sample of the type (`PlanSchemaTest.SAMPLES` holds valid JSON for each type, one sample per shape that needs different properties). The schema therefore never refuses a plan the agent accepts, and the two cannot disagree. A new type of the plan model needs a sample; the test names the type that lacks one. After changing the plan model, regenerate it:

```text
./gradlew :chaos-agent-startup-config:test --tests '*PlanSchemaTest*' -PupdatePlanSchema
```

## 2. Structure

```text
plan          = { "scenarios": [ scenario, … ]    ; required, at least one
                  [, "metadata": metadata ]
                  [, "observability": observability ] }
metadata      = { "name": string [, "description": string] }   ; name required, non-blank
observability = { "jmxEnabled": boolean, "structuredLoggingEnabled": boolean,
                  "debugDumpOnStart": boolean }       ; each optional
scenario      = { "id": string, "selector": selector, "effect": effect   ; required
                  [, "scope": "JVM" | "SESSION"]       ; default JVM
                  [, "activationPolicy": policy]       ; default: always
                  [, "description": string]
                  [, "precedence": integer]
                  [, "tags": { string: string, … }] }
selector      = { "type": selector-type, "operations": [ operation, … ], … }
                                                       ; operations required (not for stress), at least one,
                                                       ; each one that selector supports; then its own fields
effect        = { "type": effect-type, … }             ; the fields of that effect
policy        = { "probability", "activateAfterMatches", "maxApplications", "activeFor",
                  "activeFrom", "activeUntil", "rateLimit", "randomSeed",
                  "allowDestructiveEffects", "startMode", "replayMatches" }  ; each optional
rateLimit     = { "permits": integer, "window": duration }        ; both required
```

Every source (the agent's `configFile` / `configJson` / `configBase64`, the Spring `config-file`, the poller, the management endpoints, the MBean, `@WithChaos`) reads the same three shapes through `ChaosPlanMapper.read`: an object with a `scenarios`, `metadata` or `observability` member is a plan; any other object is a single scenario; an array is a list of scenarios. Bare scenarios are wrapped in a plan named `default` (`@WithChaos`: `@WithChaos`). An empty object `{}` is therefore read as a scenario, and its error names the scenario field it lacks; an empty array is refused ("scenarios must not be empty"). A byte order mark before the JSON is skipped, and a failure is reported at its line and column in the input. The schema describes the plan form.

## 3. Values

| Kind | Form | Example |
|------|------|---------|
| duration | ISO-8601 duration | `"PT0.2S"`, `"PT5M"` |
| instant | RFC 3339 / ISO-8601 instant, UTC | `"2026-11-01T08:00:00Z"` |
| operation | an `OperationType` name | `"SOCKET_CONNECT"` |
| enum | the constant's name, exactly | `"SESSION"`, `"PLATFORM"`, `"EIO"` |
| pattern | `{"mode": "EXACT" \| "PREFIX" \| "GLOB" \| "REGEX" \| "ANY", "value": string}` | `{"mode": "GLOB", "value": "*.internal"}` |

A `REGEX` runs on every evaluation of its selector, on the intercepted call's thread, with Java's backtracking engine and no time limit: the 4096-character cap does not bound a match, and nested or adjacent unbounded quantifiers (`(a+)+b`, `(.*a){12}`) can take seconds on a short name. Take REGEX patterns only from trusted authors, and prefer `GLOB`, `PREFIX` or `EXACT`.

An enum is matched exactly: `"jvm"` is refused with the constants it may be ("unknown value; expected one of JVM, SESSION"). The Quarkus `@ChaosScenario` annotation, which is not JSON, reads its `scope` attribute case-insensitively.

## 4. Strictness

- **Unknown fields are rejected** everywhere (`additionalProperties: false`): a misspelled field is an error, not a silently ignored setting.
- **Trailing content is rejected**: a file is one JSON value.
- **A field given twice is rejected**, instead of the last value winning.
- **No coercion**: a duration or an instant is an ISO-8601 string (a number such as `1` or `1e100` is refused, not read as seconds), an integer field takes an integer (not `1.5`, not `"5"`), a boolean `true` or `false` (not `1`), and an enum its name (not its ordinal).
- **All or nothing**: a plan with one invalid scenario, or one whose hooks cannot be woven, applies no scenario at all; a reloaded plan replaces the previous one only when the whole new plan is valid. The refusal is recorded once in the diagnostics and published as `FAILED`.
- **What the schema cannot say**: a rule across fields is the mapper's, not the schema's. A method selector needs a `classPattern` or a `methodNamePattern` other than `ANY`, a `dnsAnswer` one of `override`, `family`, `shuffle` and `limit`, a pattern in mode `ANY` takes no value other than a blank or `"*"`; ranges (a count above 0, a port up to 65535) are checked by the mapper too. A plan the schema accepts can still be refused by the mapper, never the reverse.
- **Syntax, then meaning**: the schema decides what is well formed; `ChaosValidationException` then rejects what is well formed but meaningless (an effect on an operation it cannot act on, a clock skew with a probability), naming the scenario. A value out of range (a negative delay, reversed delay bounds, a port above 65535) is refused when the mapper builds the record, as a `ConfigLoadException` "invalid value: <reason> at <path>".

## 5. Selector types

`async`, `awsSdk`, `classLoading`, `dns`, `executor`, `fileIo`, `httpClient`, `jdbc`, `jvmRuntime`, `messaging`, `method`, `monitor`, `netty`, `network`, `nio`, `process`, `queue`, `redis`, `rpc`, `scheduling`, `shutdown`, `ssl`, `stress`, `thread`, `threadLocal`.

## 6. Effect types

`clockSkew`, `codeCachePressure`, `connectionReset`, `corruptRead`, `deadlock`, `delay`, `directBufferPressure`, `dnsAnswer`, `dnsRewrite`, `errno`, `exceptionInjection`, `exceptionalCompletion`, `finalizerBacklog`, `gate`, `gcPressure`, `heapPressure`, `keepAlive`, `metaspacePressure`, `monitorContention`, `redirect`, `referenceQueueFlood`, `reject`, `returnValueCorruption`, `safepointStorm`, `spuriousWakeup`, `stringInternPressure`, `suppress`, `threadLeak`, `threadLocalLeak`, `timeout`, `tornWrite`, `trickle`, `virtualThreadCarrierPinning`.

## 7. A complete plan

```json
{"metadata": {"name": "replica-failover"},
 "scenarios": [
   {"id": "replica-ipv6-only", "scope": "JVM",
    "selector": {"type": "dns", "operations": ["DNS_ANSWER"],
                 "hostnamePattern": {"mode": "EXACT", "value": "replica.internal"}},
    "effect": {"type": "dnsAnswer", "family": "IPV6"}},
   {"id": "replica-refused-thrice", "scope": "JVM",
    "selector": {"type": "network", "operations": ["SOCKET_CONNECT"],
                 "remoteCidr": "::/0", "remotePort": 5432},
    "effect": {"type": "errno", "errno": "ECONNREFUSED"},
    "activationPolicy": {"maxApplications": 3}}]}
```

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
