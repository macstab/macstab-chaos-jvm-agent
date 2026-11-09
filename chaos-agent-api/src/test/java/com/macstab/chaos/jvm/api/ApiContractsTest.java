package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  /**
   * Nullness is in the types (ADR 0008): every production package is {@code @NullMarked}, and a
   * parameter that accepts {@code null} says so while the accessor of the value it normalises does
   * not.
   */
  @Nested
  @DisplayName("nullness")
  class Nullness {

    @Test
    @DisplayName("every production package of every module is @NullMarked")
    void everyPackageNullMarked() throws java.io.IOException {
      // The tests run in the module's directory; its parent is the build's root.
      final java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath().getParent();
      final List<java.nio.file.Path> packages;
      try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
        packages =
            files
                .filter(file -> file.toString().endsWith(".java"))
                .filter(file -> root.relativize(file).toString().startsWith("chaos-agent-"))
                .filter(file -> file.toString().contains("/src/main/java/"))
                .map(java.nio.file.Path::getParent)
                .distinct()
                .toList();
      }
      assertThat(packages).isNotEmpty();
      for (final java.nio.file.Path directory : packages) {
        final java.nio.file.Path info = directory.resolve("package-info.java");
        assertThat(info).as("%s", root.relativize(directory)).exists();
        assertThat(java.nio.file.Files.readString(info))
            .as("%s", root.relativize(info))
            .contains("@NullMarked");
      }
      assertThat(
              ChaosSelector.class
                  .getPackage()
                  .isAnnotationPresent(org.jspecify.annotations.NullMarked.class))
          .isTrue();
    }

    @Test
    @DisplayName("a pattern a selector normalises is @Nullable as a parameter, not as a value")
    void nullableParameterNonNullAccessor() throws NoSuchMethodException {
      final java.lang.reflect.Constructor<ChaosSelector.MessagingSelector> constructor =
          ChaosSelector.MessagingSelector.class.getDeclaredConstructor(
              java.util.Set.class, NamePattern.class);

      assertThat(
              constructor.getAnnotatedParameterTypes()[1].isAnnotationPresent(
                  org.jspecify.annotations.Nullable.class))
          .isTrue();
      assertThat(
              ChaosSelector.MessagingSelector.class
                  .getMethod("destinationPattern")
                  .getAnnotatedReturnType()
                  .isAnnotationPresent(org.jspecify.annotations.Nullable.class))
          .isFalse();
    }
  }

  /**
   * The private JSON creators the plan mapper binds (through mix-ins in chaos-agent-startup-config,
   * whose tests read the JSON): a null argument, an omitted field, takes the documented default.
   */
  @Nested
  @DisplayName("JSON creators")
  class JsonCreators {

    private <T> T create(final Class<T> type, final Class<?>[] types, final Object... args)
        throws ReflectiveOperationException {
      final java.lang.reflect.Constructor<T> creator = type.getDeclaredConstructor(types);
      creator.setAccessible(true);
      return creator.newInstance(args);
    }

    @Test
    @DisplayName("effects take their documented defaults for null and keep given values")
    void effectDefaults() throws ReflectiveOperationException {
      final Class<?>[] injection = {String.class, String.class, Boolean.class};
      assertThat(
              create(
                      ChaosEffect.ExceptionInjectionEffect.class,
                      injection,
                      "java.lang.IllegalStateException",
                      "m",
                      null)
                  .withStackTrace())
          .isTrue();
      assertThat(
              create(
                      ChaosEffect.ExceptionInjectionEffect.class,
                      injection,
                      "java.lang.IllegalStateException",
                      "m",
                      false)
                  .withStackTrace())
          .isFalse();

      final Class<?>[] metaspace = {int.class, int.class, Boolean.class};
      assertThat(create(ChaosEffect.MetaspacePressureEffect.class, metaspace, 1, 0, null).retain())
          .isTrue();
      assertThat(create(ChaosEffect.MetaspacePressureEffect.class, metaspace, 1, 0, false).retain())
          .isFalse();

      final Class<?>[] gc = {long.class, Integer.class, boolean.class, java.time.Duration.class};
      final java.time.Duration second = java.time.Duration.ofSeconds(1);
      assertThat(
              create(ChaosEffect.GcPressureEffect.class, gc, 1L, null, false, second)
                  .objectSizeBytes())
          .isEqualTo(ChaosEffect.GcPressureEffect.DEFAULT_OBJECT_SIZE_BYTES);
      assertThat(
              create(ChaosEffect.GcPressureEffect.class, gc, 1L, 7, false, second)
                  .objectSizeBytes())
          .isEqualTo(7);

      final Class<?>[] deadlock = {Integer.class, java.time.Duration.class};
      assertThat(create(ChaosEffect.DeadlockEffect.class, deadlock, 2, null).acquisitionDelay())
          .isEqualTo(ChaosEffect.DeadlockEffect.DEFAULT_ACQUISITION_DELAY);
      assertThat(
              create(ChaosEffect.DeadlockEffect.class, deadlock, 2, java.time.Duration.ZERO)
                  .acquisitionDelay())
          .isZero();
      assertThatThrownBy(() -> create(ChaosEffect.DeadlockEffect.class, deadlock, null, null))
          .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
          .hasRootCauseMessage(
              "participantCount must be >= 2 — a deadlock requires at least two participants");
    }

    @Test
    @DisplayName("observability takes true, true, false for null and keeps given values")
    void observabilityDefaults() throws ReflectiveOperationException {
      final Class<?>[] types = {Boolean.class, Boolean.class, Boolean.class};
      assertThat(create(ChaosPlan.Observability.class, types, null, null, null))
          .isEqualTo(new ChaosPlan.Observability(true, true, false));
      assertThat(create(ChaosPlan.Observability.class, types, false, false, true))
          .isEqualTo(new ChaosPlan.Observability(false, false, true));
    }

    @Test
    @DisplayName("the policy starts automatically and always fires for null, else as given")
    void policyDefaults() throws ReflectiveOperationException {
      final java.lang.reflect.Method fromJson =
          ActivationPolicy.class.getDeclaredMethod(
              "fromJson",
              ActivationPolicy.StartMode.class,
              Double.class,
              long.class,
              Long.class,
              java.time.Duration.class,
              ActivationPolicy.RateLimit.class,
              Long.class,
              boolean.class,
              Instant.class,
              Instant.class,
              List.class);
      fromJson.setAccessible(true);

      final ActivationPolicy defaults =
          (ActivationPolicy)
              fromJson.invoke(
                  null, null, null, 0L, null, null, null, null, false, null, null, null);
      assertThat(defaults.startMode()).isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
      assertThat(defaults.probability()).isEqualTo(1.0d);

      final ActivationPolicy given =
          (ActivationPolicy)
              fromJson.invoke(
                  null,
                  ActivationPolicy.StartMode.MANUAL,
                  0.5d,
                  0L,
                  null,
                  null,
                  null,
                  null,
                  false,
                  null,
                  null,
                  null);
      assertThat(given.startMode()).isEqualTo(ActivationPolicy.StartMode.MANUAL);
      assertThat(given.probability()).isEqualTo(0.5d);
    }
  }

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
}
