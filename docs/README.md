# Docs

This directory is organized by architectural boundary, not by package inventory. Start from what you want to do.

## Use the agent

- Read the [User Guide](user-guide.md) for writing scenarios, the testkit and the recipes.
- Read the [Configuration Reference](configuration-reference.md) for every selector, effect and policy, and which effect applies to which operation.
- The plan format, with its published JSON Schema: [Plan Schema](plan-schema.md).
- Read [Spring Integration](spring-integration.md) for the Spring Boot 3 and 4 starters, `@ChaosTest` and the Actuator endpoint.
- Read [Testkit](testkit.md) for test-local installation and JUnit integration behavior.
- Upgrading from 1.x: [Migration to 2.0](migration-2.0.md).

## Run it and see what it does

- Read [Startup Config](startup-config.md) for configuration resolution and serialization behavior.
- Read [Observability](observability.md) for the status and hits files, events, metrics, JMX and handle counters.
- Read [Benchmarks](benchmarks.md) for what the agent costs on the hot path and how it is measured.
- Read [Cross-Layer](cross-layer.md) for the mapping between the native chaos libraries' rules and this agent's API.

## Understand how it works

- Read [Semantics](semantics.md) for the rules every evaluation follows, stated as numbered laws.
- Start with [Overall Agent](overall-agent.md) for system purpose, runtime flow, trust boundaries, concurrency model, failure model, and operational caveats.
- Read [API](api.md) for the stable contract boundary exposed to callers and configuration producers.
- Read [Bootstrap](bootstrap.md), [Core](core.md), and [Instrumentation](instrumentation.md) for the internal mechanics that make the agent behave the way it does at runtime.
- Read [Internals](internals.md) for what happens underneath: the evaluation pipeline step by step, the class-loader bridge, and the JVM mechanisms the agent relies on.
- Why the agent is built the way it is: [Architecture Decision Records](adr/README.md).

## Contract surface

Modules whose public API is recorded in `api/<module>.api` and checked by `apiCheck` (a change needs `./gradlew apiDump`):

- `chaos-agent-api` — the stable contract for callers and configuration producers
- `chaos-agent-startup-config`, `chaos-agent-testkit`
- the framework integrations: `chaos-agent-spring-boot*`, `chaos-agent-quarkus-extension`, `chaos-agent-quarkus-management`, `chaos-agent-micronaut-integration`, `chaos-agent-micrometer`, `chaos-agent-opentelemetry` (the Quarkus `-deployment` modules hold build steps only Quarkus calls and are not API)

Implementation modules that should be treated as internal unless the project explicitly commits otherwise:

- `chaos-agent-bootstrap`
- `chaos-agent-core`
- `chaos-agent-instrumentation-jdk`
