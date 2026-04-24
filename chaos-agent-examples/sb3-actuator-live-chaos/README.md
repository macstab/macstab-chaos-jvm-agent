# sb3-actuator-live-chaos

## What this example demonstrates

A Spring Boot 3 payment service calls a downstream payment gateway through Resilience4j's circuit
breaker. The chaos agent injects `ConnectException` on every `SOCKET_CONNECT` via the
`ChaosSession` API, which causes all HTTP calls to the gateway to fail immediately. After five
failures the circuit breaker opens and all subsequent requests are served by the fallback method
(`"fallback:circuit-open"`) without touching the network at all.

The example demonstrates:
- Session-scoped chaos activation in a `@ChaosTest` integration test
- How network-layer fault injection (socket-level) propagates through Spring's `RestTemplate` and
  surfaces as `ResourceAccessException`, triggering Resilience4j's failure-rate counter
- How to verify that the fallback path actually executes — not just that no exception escapes

## Application components

| Class | Purpose |
|---|---|
| `PaymentServiceApplication` | Spring Boot entry point |
| `AppConfig` | `RestTemplate` bean |
| `PaymentGatewayClient` | `@CircuitBreaker`-decorated client |
| `PaymentController` | `POST /payments/{orderId}` endpoint |

## Prerequisites

- JDK 21+
- The chaos agent jar on the class/module path (provided by `chaos-agent-spring-boot3-starter`)

## Running the integration test

```bash
./gradlew :chaos-agent-examples:sb3-actuator-live-chaos:test
```

## Expected output

The test fires six requests while chaos is active and asserts that at least one response body
contains `"fallback:circuit-open"`. After `handle.stop()` is called the chaos scenarios are
deactivated and subsequent requests reach WireMock normally.

Console output from Resilience4j's circuit breaker state transitions is printed to the test log.
Look for lines like:

```
CircuitBreaker 'payment-gateway' changed state from CLOSED to OPEN
```

## Key configuration

`application.properties` sets `slidingWindowSize=5` and `failureRateThreshold=60` so the circuit
opens after three failures in five requests — achievable in a single test method with six calls.
