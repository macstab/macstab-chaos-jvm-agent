<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0006 — Dispatch is one engine and ten domain dispatchers

**Status:** Accepted (2.0)

## Context

`ChaosDispatcher` had grown to every interception point of every domain in one class: thread, executor, method, clock, runtime, network, DNS, file, process and client calls, with the evaluation and composition logic they share in between. A change to one domain meant reading all of them.

## Decision

`DispatchEngine` holds what every dispatch needs: the idle check, evaluation, composition of contributions (L11–L14), the terminal actions, delay and gate. Ten domain classes (`ThreadDispatch` … `ClientDispatch`) hold the interception points of one domain each and depend only on the engine. `ChaosDispatcher` is the composition root; `ChaosRuntime` keeps a field per domain so a call takes no extra hop.

## Consequences

- A domain is read and changed on its own; `ArchitectureTest` keeps the domains from depending on each other.
- The idle cost per call is unchanged (benchmarked before and after the split).

## Alternatives rejected

- **One class per operation**: more than seventy classes for code whose structure is per domain.
