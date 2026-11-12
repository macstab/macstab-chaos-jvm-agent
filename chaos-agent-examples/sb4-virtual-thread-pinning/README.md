# sb4-virtual-thread-pinning

## What this example demonstrates

Spring Boot 4 enables virtual threads by default (`spring.threads.virtual.enabled=true`). Virtual
threads can be *pinned* to their carrier platform thread when they enter a `synchronized` block —
the JVM cannot unmount a virtual thread that is inside a monitor. Under heavy lock contention this
effectively reduces the carrier pool and starves other virtual threads waiting to be scheduled.

`MetricsAggregator` uses `synchronized(lock)` intentionally: it represents a common production
pattern where a service accumulates counters in a guarded `HashMap`. At startup, if
`example.pinning.enabled=true`, `ChaosStartupPlan` activates a
`VirtualThreadCarrierPinningEffect` that pins two of the virtual-thread carriers for 25 ms bursts,
so the request handlers' virtual threads wait for a free carrier.

The integration test submits 100 concurrent `POST /metrics?name=test` calls on a
`newVirtualThreadPerTaskExecutor()` and asserts:
1. The pinning scenario is ACTIVE before and after the burst (the property switched it on).
2. All 100 requests succeed (no 5xx).
3. The snapshot shows a count of 100 for the `test` key.

The wall-clock time is printed for observation — the test is a demonstration of degradation under
contention, not a strict latency gate.

## Application components

| Class | Purpose |
|---|---|
| `VirtualThreadPinningApplication` | Spring Boot entry point |
| `MetricsAggregator` | Synchronized metric counter — the pinning target |
| `MetricsController` | `POST /metrics?name=` and `GET /metrics/snapshot` |
| `ChaosStartupPlan` | Activates `VirtualThreadCarrierPinningEffect` on `ApplicationReadyEvent` |

## Prerequisites

- JDK 21+ (virtual threads and `synchronized`-pinning detection available via JFR)
- `chaos-agent-spring-boot4-starter` on the module path

## Running the integration test

```bash
./gradlew :chaos-agent-examples:sb4-virtual-thread-pinning:test
```

## Expected output

```
[VirtualThreadPinningIT] 100 concurrent POST /metrics under carrier pinning: 178 ms wall-clock
```

One run on JDK 25 (Apple Silicon); the number varies by hardware, and the test logs it without
gating on it. Compare it with a run without the property to see what the pinned carriers cost.

Enable JFR virtual-thread pinning events (`jdk.VirtualThreadPinned`) for a flamegraph showing
exactly which `synchronized` blocks cause pinning.

## Disabling chaos at runtime

The `example.pinning.enabled` property defaults to `false` so the service starts cleanly
without contention when used outside test scope.
