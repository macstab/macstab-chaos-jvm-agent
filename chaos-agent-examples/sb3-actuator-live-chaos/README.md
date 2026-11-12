# sb3-actuator-live-chaos

## What this example demonstrates

A Spring Boot 3 payment service calls a downstream payment gateway (WireMock on a free port)
through Resilience4j's circuit breaker. The test first charges an order without chaos (the gateway
answers `"charged"`), then activates a plan through the live actuator endpoint, `POST
/actuator/chaos`: it rejects every `SOCKET_CONNECT` to the gateway's port with a
`ConnectException`. Once at least three calls are in the circuit breaker's window and 60 % of them
failed (with the clean call in the window, after the second rejected one), the circuit opens and the
next requests are served by the fallback method (`"fallback:circuit-open"`) without touching the network. The test
stops the plan with `DELETE /actuator/chaos/plan:gateway-down`.

The example demonstrates:
- Activating and stopping a plan at run time through `/actuator/chaos`
- How network-layer fault injection (socket-level) propagates through Spring's `RestTemplate` and
  surfaces as `ResourceAccessException`, triggering Resilience4j's failure-rate counter
- How to verify that chaos applied (the scenario's applied count) and that the fallback path
  actually executes — not just that no exception escapes

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

The test asserts that the clean charge answers `"charged"`, that the actuator answers 200 with the
plan's handle id, that the scenario's applied count is positive and that at least one of six
charges under chaos answers `"fallback:circuit-open"`. After the `DELETE` the scenario is gone.

Console output from Resilience4j's circuit breaker state transitions is printed to the test log.
Look for lines like:

```
CircuitBreaker 'payment-gateway' changed state from CLOSED to OPEN
```

## Key configuration

`application.properties` sets `slidingWindowSize=5` and `failureRateThreshold=60` so the circuit
opens once at least three calls were made and 60 % of them failed — achievable in a single test
method with one clean call and six calls under chaos.
