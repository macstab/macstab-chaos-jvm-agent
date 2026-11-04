<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# Architecture Decision Records

The decisions that shape the agent, each with the forces behind it, what was chosen, what it costs, and what was rejected. A record is not edited once accepted; a decision that changes gets a new record that supersedes the old one.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-bootstrap-bridge.md) | Advice reaches the agent through a bootstrap-loaded bridge of method-handle slots | Accepted |
| [0002](0002-on-demand-weaving.md) | Hook groups are woven on the first activation that needs them | Accepted |
| [0003](0003-shaded-agent-jar.md) | The agent jar shades ByteBuddy and Jackson and declares no dependencies | Accepted |
| [0004](0004-plan-json-by-mixins.md) | The API carries no JSON annotations; the plan format is defined by mix-ins | Accepted |
| [0005](0005-real-faults.md) | Faults are the JDK's own failures, raised in the real call | Accepted |
| [0006](0006-dispatch-domains.md) | Dispatch is one engine and ten domain dispatchers | Accepted |
| [0007](0007-documentation-from-code.md) | Reference documentation is checked against, or generated from, the code | Accepted |
