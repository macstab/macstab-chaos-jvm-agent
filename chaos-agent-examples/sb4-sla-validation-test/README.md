# sb4-sla-validation-test

## What this example demonstrates

A Spring Boot 4 service fans out to three downstream endpoints concurrently using virtual threads
and `CompletableFuture`. Chaos injects random delays of 20–80 ms on `SOCKET_READ` operations at
40% probability, simulating adverse network conditions such as TCP receive-buffer throttling or
a slow upstream proxy.

The test runs 50 sequential iterations of `GET /fanout` under active chaos and asserts that the
P99 latency across all iterations stays below 500 ms. Because all three fan-out legs run in
parallel, the per-request wall time is dominated by `max(latencyA, latencyB, latencyC)` — only
the worst-case leg matters. With 40% probability and 80 ms max delay per read, the statistical
expectation is well within the 500 ms SLA.

This pattern encodes the SLA directly in the test suite: if latency regresses (e.g. a developer
accidentally makes the fan-out sequential), the test fails even without chaos. With chaos active
it also validates that the service's concurrency model holds under realistic network jitter.

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
[SlaValidationTest] 50 iterations, P99 latency = 187 ms (SLA: 500 ms)
```

The exact P99 depends on the delay distribution and host performance. With 40% probability and
an 80 ms max per socket read, a three-leg fan-out typically peaks at 80–160 ms wall time.

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
