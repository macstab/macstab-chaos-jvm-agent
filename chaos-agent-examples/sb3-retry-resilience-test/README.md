# sb3-retry-resilience-test

## What this example demonstrates

Resilience4j `@Retry` is a common pattern to absorb transient network errors, but teams often
test it only with mocked exceptions — never with real socket-level failures. This example proves
that the retry policy works when `ConnectException` is injected at the JVM socket layer, not at
the HTTP client layer.

The chaos agent intercepts the `SOCKET_CONNECT` operations to the downstream's port (WireMock on a
free port) and throws `ConnectException` on the first two (`maxApplications` 2). Spring's
`RestTemplate` wraps the raw `ConnectException` in a `ResourceAccessException` (which extends
`IOException`). Resilience4j is configured to retry on `IOException`, so the first two of the 3
allowed attempts fail and the third gets through because the chaos cap is exhausted.

The test asserts that `GET /fetch` returns 200 with body `"pong"`, and that the scenario rejected
the first two connects (it ends with its second application, so the third connect is neither
matched nor rejected) — proving the retry policy absorbed the injected failures, not a downstream
that happened to answer.

## Application components

| Class | Purpose |
|---|---|
| `RetryDemoApplication` | Spring Boot entry point |
| `AppConfig` | `RestTemplate` bean |
| `DownstreamClient` | `@Retry(name="downstream")`-decorated HTTP client |
| `DownstreamController` | `GET /fetch` endpoint |

## Prerequisites

- JDK 21+
- `chaos-agent-spring-boot3-test-starter` on the test classpath
- WireMock standalone (included via `build.gradle.kts`)

## Running the test

```bash
./gradlew :chaos-agent-examples:sb3-retry-resilience-test:test
```

## Expected output

The test produces no special console output on success. To observe retry attempts, add
`logging.level.io.github.resilience4j=DEBUG` to `application.properties` and re-run:

```
DEBUG Retry 'downstream' recorded a failed retry attempt. ...
DEBUG Retry 'downstream' recorded a failed retry attempt. ...
DEBUG Retry 'downstream' recorded a successful retry attempt. ...
```

## Retry configuration

`application.properties` configures `maxAttempts=3` and `waitDuration=50ms`. The chaos cap of 2
ensures the third attempt always succeeds. To test full retry exhaustion, raise `maxApplications`
above `maxAttempts` and observe the exception propagate to the controller.

## Key insight

Testing retry with real socket failures (not mocked `RestClientException`) validates the full
exception wrapping path: `ConnectException` → `ResourceAccessException` → Resilience4j retry
trigger. A mock-only test would miss a misconfigured `retryExceptions` list that omits
`IOException`.
