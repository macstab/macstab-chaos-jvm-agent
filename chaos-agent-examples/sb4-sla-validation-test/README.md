# sb4-sla-validation-test

## What this example demonstrates

A Spring Boot 4 service fans out to three downstream endpoints (WireMock servers on free ports)
concurrently using virtual threads and `CompletableFuture`, through the JDK `HttpClient`. Two
tests run `GET /fanout` 30 times per measurement:

- `probabilisticDelayStaysWithin500msOfBaseline` measures the P99 without chaos, then delays each
  `HTTP_CLIENT_SEND` by 20–80 ms with probability 0.4 and asserts that the P99 stays within 500 ms
  of the baseline P99, and that the delay applied. Because all three fan-out legs run in
  parallel, the per-request wall time is dominated by the slowest leg.
- `graduatedDelayLadderStacksAdditively` stacks one, two and three scenarios that each delay every
  `HTTP_CLIENT_SEND` by 120 ms, and asserts that each step raises the P99 by at least 96 ms per
  scenario over the baseline median.

This pattern encodes the SLA directly in the test suite: if latency regresses (e.g. a developer
accidentally makes the fan-out sequential), the budget is exceeded under chaos.

## Application components

| Class | Purpose |
|---|---|
| `SlaApplication` | Spring Boot entry point |
| `FanOutResult` | Record carrying results from all three legs |
| `AppConfig` | `HttpClient` bean (NIO; routes through instrumented `SocketChannelImpl`) |
| `FanOutService` | Concurrent fan-out via `CompletableFuture` + virtual threads |
| `FanOutController` | `GET /fanout` endpoint |
| `LatencyStats` | `percentile(sortedNanos, pct)` utility |

## Prerequisites

- JDK 21+ (virtual threads required)
- `chaos-agent-spring-boot4-test-starter` on the test classpath
- WireMock standalone (included via `build.gradle.kts`)

## Running the test

```bash
./gradlew :chaos-agent-examples:sb4-sla-validation-test:test
```

## Expected output

```
baseline P99=10ms  chaos P99=86ms
P99 ladder (0 = baseline median): 0=2ms  1=132ms  2=249ms  3=369ms  (threshold/step=96ms)
```

One run on JDK 25 (Apple Silicon); the exact numbers depend on the host. With probability 0.4 and an 80 ms maximum per call, a
three-leg fan-out typically adds up to 80 ms to the slowest leg.

## Tuning the scenario

To stress the SLA, increase the delay range or probability in `SlaValidationTest`:

```java
.effect(ChaosEffect.delay(Duration.ofMillis(100), Duration.ofMillis(400)))
.activationPolicy(new ActivationPolicy(..., 0.9, ...))
```

A 90% probability of 100–400 ms delays will push P99 above 400 ms on most hardware, demonstrating
where the fan-out concurrency model stops being sufficient.

## Why P99 and not average

Average latency hides tail behavior. A service that responds in 10 ms on 98% of requests and
5000 ms on 2% has an acceptable average but an unacceptable P99. Encoding the P99 SLA in tests
catches tail regressions early.
