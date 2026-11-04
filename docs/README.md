# Docs

This directory is organized by architectural boundary, not by package inventory.

- Upgrading from 1.x: [Migration to 2.0](migration-2.0.md).
- The plan format, with its published JSON Schema: [Plan Schema](plan-schema.md).
- Why the agent is built the way it is: [Architecture Decision Records](adr/README.md).
- Start with [Overall Agent](overall-agent.md) for system purpose, runtime flow, trust boundaries, concurrency model, failure model, and operational caveats.
- Read [API](api.md) for the stable contract boundary exposed to callers and configuration producers.
- Read [Bootstrap](bootstrap.md), [Core](core.md), and [Instrumentation](instrumentation.md) for the internal mechanics that make the agent behave the way it does at runtime.
- Read [Startup Config](startup-config.md) for configuration resolution and serialization behavior.
- Read [Testkit](testkit.md) for test-local installation and JUnit integration behavior.


Stable external contract surface:

- `chaos-agent-api`

Implementation modules that should be treated as internal unless the project explicitly commits otherwise:

- `chaos-agent-bootstrap`
- `chaos-agent-core`
- `chaos-agent-instrumentation-jdk`
- `chaos-agent-startup-config`
- `chaos-agent-testkit`

