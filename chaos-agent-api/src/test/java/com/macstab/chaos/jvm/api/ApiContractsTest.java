package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Smaller contracts of the API: replay policies, listeners, default methods, regex patterns. */
@DisplayName("API contracts")
class ApiContractsTest {

  @Nested
  @DisplayName("replay matches")
  class ReplayMatches {

    @Test
    @DisplayName("match number zero is rejected; match numbers start at one")
    void zeroIsRejected() {
      assertThatThrownBy(() -> ActivationPolicy.builder().replayMatches(List.of(0L)).build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("replayMatches must hold positive match numbers");
      assertThat(ActivationPolicy.builder().replayMatches(List.of(1L)).build().replayMatches())
          .containsExactly(1L);
    }

    @Test
    @DisplayName("a match inside the warm-up is rejected; the first one after it is accepted")
    void warmUp() {
      assertThatThrownBy(
              () ->
                  ActivationPolicy.builder()
                      .activateAfterMatches(3)
                      .replayMatches(List.of(5L, 3L))
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("replayMatches 3 lies inside the warm-up of 3 matches and could never fire");
      assertThat(
              ActivationPolicy.builder()
                  .activateAfterMatches(3)
                  .replayMatches(List.of(6L, 4L, 4L))
                  .build()
                  .replayMatches())
          .containsExactly(4L, 6L);
    }
  }

  @Nested
  @DisplayName("ActivationPolicy construction")
  class PolicyConstruction {

    @Test
    @DisplayName("fromJson fills a missing start mode and probability with the defaults")
    void fromJsonDefaults() {
      final ActivationPolicy policy =
          ActivationPolicy.fromJson(
              null, null, 0L, null, null, null, null, false, null, null, null);

      assertThat(policy.startMode()).isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
      assertThat(policy.probability()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("fromJson keeps every given field")
    void fromJsonKeeps() {
      final ActivationPolicy.RateLimit rateLimit =
          new ActivationPolicy.RateLimit(2, Duration.ofSeconds(1));
      final ActivationPolicy policy =
          ActivationPolicy.fromJson(
              ActivationPolicy.StartMode.MANUAL,
              0.5d,
              2L,
              7L,
              Duration.ofMinutes(1),
              rateLimit,
              42L,
              true,
              null,
              null,
              null);

      assertThat(policy)
          .isEqualTo(
              new ActivationPolicy(
                  ActivationPolicy.StartMode.MANUAL,
                  0.5d,
                  2L,
                  7L,
                  Duration.ofMinutes(1),
                  rateLimit,
                  42L,
                  true,
                  null,
                  null,
                  null));
    }

    @Test
    @DisplayName("the builder takes a ready rate limit")
    void builderTakesRateLimit() {
      final ActivationPolicy.RateLimit rateLimit =
          new ActivationPolicy.RateLimit(3, Duration.ofSeconds(2));

      assertThat(ActivationPolicy.builder().rateLimit(rateLimit).build().rateLimit())
          .isEqualTo(rateLimit);
    }
  }

  @Nested
  @DisplayName("ChaosDecisionRecorder per session")
  class RecorderSessions {

    private ChaosEvent applied(final String match, final String sessionId) {
      final Map<String, String> attributes =
          sessionId == null
              ? Map.of("match", match)
              : Map.of("match", match, "sessionId", sessionId);
      return new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.APPLIED, "a", "applied", attributes);
    }

    @Test
    @DisplayName("matches are kept apart by session; no session is its own bucket")
    void bySession() {
      final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
      recorder.onEvent(applied("3", "s1"));
      recorder.onEvent(applied("1", "s1"));
      recorder.onEvent(applied("2", "s2"));
      recorder.onEvent(applied("9", null));

      assertThat(recorder.matches("a", "s1")).containsExactly(1L, 3L);
      assertThat(recorder.matches("a", "s2")).containsExactly(2L);
      assertThat(recorder.matches("a", null)).containsExactly(9L);
      assertThat(recorder.matches("a", "s3")).isEmpty();
      assertThat(recorder.matches("unknown", "s1")).isEmpty();
      assertThat(recorder.matches("a")).containsExactly(1L, 2L, 3L, 9L);
    }
  }

  @Nested
  @DisplayName("listeners and default methods")
  class Defaults {

    @Test
    @DisplayName("an async listener wraps its delegate and hands every event to it")
    void asyncListener() {
      final List<ChaosEvent> seen = new ArrayList<>();
      final ChaosEventListener delegate = seen::add;
      final ChaosEvent event =
          new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.STARTED, "a", "started", Map.of());

      final ChaosEventListener async = ChaosEventListener.async(delegate);
      async.onEvent(event);

      assertThat(async).isInstanceOf(AsyncChaosEventListener.class);
      assertThat(((AsyncChaosEventListener) async).delegate()).isSameAs(delegate);
      assertThat(seen).containsExactly(event);
    }

    @Test
    @DisplayName("closing an activation handle stops it")
    void closeStops() {
      final AtomicInteger stops = new AtomicInteger();
      final ChaosActivationHandle handle = new StubHandle(stops);

      handle.close();

      assertThat(stops).hasValue(1);
    }

    @Test
    @DisplayName("a control plane without sessions binds to a no-op binding")
    void defaultBindSession() {
      final ChaosControlPlane plane = new StubControlPlane();

      final ChaosSession.ScopeBinding binding = plane.bindSession("any");

      assertThat(binding).isNotNull();
      assertThatCode(binding::close).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("a regex pattern compiled for the first time matches straight away")
  void freshRegexMatches() {
    final String id = UUID.randomUUID().toString();
    final NamePattern pattern = NamePattern.regex("svc-" + id + "-\\d+");

    assertThat(pattern.matches("svc-" + id + "-42")).isTrue();
    assertThat(pattern.matches("svc-" + id + "-x")).isFalse();
  }

  private static final class StubHandle implements ChaosActivationHandle {
    private final AtomicInteger stops;

    StubHandle(final AtomicInteger stops) {
      this.stops = stops;
    }

    @Override
    public String id() {
      return "stub";
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
      stops.incrementAndGet();
    }

    @Override
    public void release() {}

    @Override
    public ChaosDiagnostics.ScenarioState state() {
      return ChaosDiagnostics.ScenarioState.ACTIVE;
    }

    @Override
    public long matchedCount() {
      return 0;
    }

    @Override
    public long appliedCount() {
      return 0;
    }
  }

  private static final class StubControlPlane implements ChaosControlPlane {
    @Override
    public ChaosActivationHandle activate(final ChaosScenario scenario) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosActivationHandle activate(final ChaosPlan plan) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosSession openSession(final String displayName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChaosDiagnostics diagnostics() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void addEventListener(final ChaosEventListener listener) {}

    @Override
    public boolean removeEventListener(final ChaosEventListener listener) {
      return false;
    }

    @Override
    public void close() {}
  }
}
