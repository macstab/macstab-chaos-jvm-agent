package io.macstab.chaos.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChaosEffectValidationTest {

  // ── DelayEffect ──────────────────────────────────────────────────────────

  @Test
  void delayEffectValidConstruction() {
    assertDoesNotThrow(
        () -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), Duration.ofMillis(100)));
  }

  @Test
  void delayEffectZeroDelayIsValid() {
    assertDoesNotThrow(
        () -> new ChaosEffect.DelayEffect(Duration.ZERO, Duration.ZERO));
  }

  @Test
  void delayEffectNullMinThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DelayEffect(null, Duration.ofMillis(100)));
  }

  @Test
  void delayEffectNullMaxThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), null));
  }

  @Test
  void delayEffectNegativeMinThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DelayEffect(Duration.ofMillis(-1), Duration.ofMillis(100)));
  }

  @Test
  void delayEffectNegativeMaxThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), Duration.ofMillis(-1)));
  }

  @Test
  void delayEffectMaxLessThanMinThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DelayEffect(Duration.ofMillis(200), Duration.ofMillis(100)));
  }

  @Test
  void delayFactoryFixedCreatesEqualBounds() {
    ChaosEffect.DelayEffect effect = ChaosEffect.delay(Duration.ofMillis(50));
    assertEquals(Duration.ofMillis(50), effect.minDelay());
    assertEquals(Duration.ofMillis(50), effect.maxDelay());
  }

  @Test
  void delayFactoryRangeCreatesCorrectBounds() {
    ChaosEffect.DelayEffect effect =
        ChaosEffect.delay(Duration.ofMillis(10), Duration.ofMillis(200));
    assertEquals(Duration.ofMillis(10), effect.minDelay());
    assertEquals(Duration.ofMillis(200), effect.maxDelay());
  }

  @Test
  void delayFactoryProducesDelayEffectInstance() {
    assertInstanceOf(ChaosEffect.DelayEffect.class, ChaosEffect.delay(Duration.ofMillis(1)));
  }

  // ── GateEffect ───────────────────────────────────────────────────────────

  @Test
  void gateEffectNullMaxBlockIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.GateEffect(null));
  }

  @Test
  void gateEffectPositiveMaxBlockIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.GateEffect(Duration.ofSeconds(5)));
  }

  @Test
  void gateEffectZeroMaxBlockThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.GateEffect(Duration.ZERO));
  }

  @Test
  void gateEffectNegativeMaxBlockThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GateEffect(Duration.ofMillis(-1)));
  }

  @Test
  void gateFactoryProducesGateEffectInstance() {
    assertInstanceOf(ChaosEffect.GateEffect.class, ChaosEffect.gate(null));
    assertInstanceOf(ChaosEffect.GateEffect.class, ChaosEffect.gate(Duration.ofSeconds(10)));
  }

  // ── RejectEffect ─────────────────────────────────────────────────────────

  @Test
  void rejectEffectValidMessage() {
    assertDoesNotThrow(() -> new ChaosEffect.RejectEffect("chaos rejection"));
  }

  @Test
  void rejectEffectNullMessageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ChaosEffect.RejectEffect(null));
  }

  @Test
  void rejectEffectBlankMessageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ChaosEffect.RejectEffect("   "));
  }

  @Test
  void rejectEffectEmptyMessageThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ChaosEffect.RejectEffect(""));
  }

  @Test
  void rejectFactoryProducesRejectEffectInstance() {
    assertInstanceOf(ChaosEffect.RejectEffect.class, ChaosEffect.reject("msg"));
  }

  // ── SuppressEffect ───────────────────────────────────────────────────────

  @Test
  void suppressEffectConstructionIsValid() {
    assertDoesNotThrow(ChaosEffect.SuppressEffect::new);
  }

  @Test
  void suppressFactoryProducesSuppressEffectInstance() {
    assertInstanceOf(ChaosEffect.SuppressEffect.class, ChaosEffect.suppress());
  }

  // ── ExceptionalCompletionEffect ───────────────────────────────────────────

  @Test
  void exceptionalCompletionValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.ExceptionalCompletionEffect(
                ChaosEffect.FailureKind.TIMEOUT, "simulated timeout"));
  }

  @Test
  void exceptionalCompletionNullKindThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionalCompletionEffect(null, "msg"));
  }

  @Test
  void exceptionalCompletionNullMessageThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionalCompletionEffect(ChaosEffect.FailureKind.IO, null));
  }

  @Test
  void exceptionalCompletionBlankMessageThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosEffect.ExceptionalCompletionEffect(
                ChaosEffect.FailureKind.REJECTED, "   "));
  }

  @Test
  void exceptionalCompletionFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.ExceptionalCompletionEffect.class,
        ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.RUNTIME, "err"));
  }

  // ── ExceptionInjectionEffect ──────────────────────────────────────────────

  @Test
  void exceptionInjectionValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.ExceptionInjectionEffect(
                "java.io.IOException", "simulated IO failure", true));
  }

  @Test
  void exceptionInjectionNullClassNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionInjectionEffect(null, "msg", true));
  }

  @Test
  void exceptionInjectionBlankClassNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionInjectionEffect("   ", "msg", true));
  }

  @Test
  void exceptionInjectionInvalidBinaryNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionInjectionEffect("123.Bad", "msg", true));
  }

  @Test
  void exceptionInjectionNullMessageThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", null, true));
  }

  @Test
  void exceptionInjectionBlankMessageThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "", true));
  }

  @Test
  void exceptionInjectionFactoryCreatesWithStackTrace() {
    ChaosEffect.ExceptionInjectionEffect effect =
        ChaosEffect.injectException("java.lang.RuntimeException", "chaos");
    assertEquals("java.lang.RuntimeException", effect.exceptionClassName());
    assertEquals("chaos", effect.message());
    assertInstanceOf(ChaosEffect.ExceptionInjectionEffect.class, effect);
  }

  // ── ReturnValueCorruptionEffect ───────────────────────────────────────────

  @Test
  void returnValueCorruptionValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.ReturnValueCorruptionEffect(
                ChaosEffect.ReturnValueStrategy.NULL));
  }

  @Test
  void returnValueCorruptionNullStrategyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ReturnValueCorruptionEffect(null));
  }

  @Test
  void returnValueCorruptionAllStrategiesAreValid() {
    for (ChaosEffect.ReturnValueStrategy strategy : ChaosEffect.ReturnValueStrategy.values()) {
      assertDoesNotThrow(() -> new ChaosEffect.ReturnValueCorruptionEffect(strategy));
    }
  }

  @Test
  void returnValueCorruptionFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.ReturnValueCorruptionEffect.class,
        ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO));
  }

  // ── ClockSkewEffect ───────────────────────────────────────────────────────

  @Test
  void clockSkewValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.ClockSkewEffect(
                Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED));
  }

  @Test
  void clockSkewNegativeAmountIsValid() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.ClockSkewEffect(
                Duration.ofSeconds(-60), ChaosEffect.ClockSkewMode.DRIFT));
  }

  @Test
  void clockSkewNullAmountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ClockSkewEffect(null, ChaosEffect.ClockSkewMode.FIXED));
  }

  @Test
  void clockSkewZeroAmountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ClockSkewEffect(Duration.ZERO, ChaosEffect.ClockSkewMode.FIXED));
  }

  @Test
  void clockSkewNullModeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ClockSkewEffect(Duration.ofSeconds(1), null));
  }

  @Test
  void clockSkewFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.ClockSkewEffect.class,
        ChaosEffect.skewClock(Duration.ofSeconds(10), ChaosEffect.ClockSkewMode.FREEZE));
  }

  // ── HeapPressureEffect ────────────────────────────────────────────────────

  @Test
  void heapPressureValidConstruction() {
    assertDoesNotThrow(() -> new ChaosEffect.HeapPressureEffect(1024L * 1024, 4096));
  }

  @Test
  void heapPressureZeroBytesThrows() {
    assertThrows(IllegalArgumentException.class, () -> new ChaosEffect.HeapPressureEffect(0, 512));
  }

  @Test
  void heapPressureNegativeBytesThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.HeapPressureEffect(-1, 512));
  }

  @Test
  void heapPressureZeroChunkSizeThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.HeapPressureEffect(1024L, 0));
  }

  @Test
  void heapPressureNegativeChunkSizeThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.HeapPressureEffect(1024L, -1));
  }

  @Test
  void heapPressureFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.HeapPressureEffect.class,
        ChaosEffect.heapPressure(1024L * 1024, 4096));
  }

  // ── KeepAliveEffect ───────────────────────────────────────────────────────

  @Test
  void keepAliveValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.KeepAliveEffect("chaos-thread", true, Duration.ofSeconds(1)));
  }

  @Test
  void keepAliveNullThreadNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.KeepAliveEffect(null, true, Duration.ofSeconds(1)));
  }

  @Test
  void keepAliveBlankThreadNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.KeepAliveEffect("  ", true, Duration.ofSeconds(1)));
  }

  @Test
  void keepAliveNullHeartbeatThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.KeepAliveEffect("t", true, null));
  }

  @Test
  void keepAliveZeroHeartbeatThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.KeepAliveEffect("t", true, Duration.ZERO));
  }

  @Test
  void keepAliveNegativeHeartbeatThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.KeepAliveEffect("t", true, Duration.ofMillis(-1)));
  }

  @Test
  void keepAliveFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.KeepAliveEffect.class,
        ChaosEffect.keepAlive("t", false, Duration.ofSeconds(1)));
  }

  // ── MetaspacePressureEffect ───────────────────────────────────────────────

  @Test
  void metaspacePressureValidConstruction() {
    assertDoesNotThrow(() -> new ChaosEffect.MetaspacePressureEffect(10, 5, true));
  }

  @Test
  void metaspacePressureZeroFieldsPerClassIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.MetaspacePressureEffect(10, 0, false));
  }

  @Test
  void metaspacePressureZeroCountThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.MetaspacePressureEffect(0, 5, true));
  }

  @Test
  void metaspacePressureNegativeCountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MetaspacePressureEffect(-1, 5, true));
  }

  @Test
  void metaspacePressureNegativeFieldsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MetaspacePressureEffect(10, -1, true));
  }

  @Test
  void metaspacePressureFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.MetaspacePressureEffect.class, ChaosEffect.metaspacePressure(5, 2));
  }

  // ── DirectBufferPressureEffect ────────────────────────────────────────────

  @Test
  void directBufferPressureValidConstruction() {
    assertDoesNotThrow(
        () -> new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false));
  }

  @Test
  void directBufferPressureZeroTotalBytesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DirectBufferPressureEffect(0, 512, false));
  }

  @Test
  void directBufferPressureNegativeTotalBytesThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DirectBufferPressureEffect(-1, 512, false));
  }

  @Test
  void directBufferPressureZeroBufferSizeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DirectBufferPressureEffect(1024L, 0, false));
  }

  @Test
  void directBufferPressureBufferSizeExceedsTotalThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DirectBufferPressureEffect(100L, 200, false));
  }

  @Test
  void directBufferPressureFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.DirectBufferPressureEffect.class,
        ChaosEffect.directBufferPressure(1024L, 512));
  }

  // ── GcPressureEffect ─────────────────────────────────────────────────────

  @Test
  void gcPressureValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.GcPressureEffect(
                1_000_000L, 1024, false, Duration.ofSeconds(5)));
  }

  @Test
  void gcPressureZeroRateThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GcPressureEffect(0, 1024, false, Duration.ofSeconds(5)));
  }

  @Test
  void gcPressureNegativeRateThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GcPressureEffect(-1, 1024, false, Duration.ofSeconds(5)));
  }

  @Test
  void gcPressureZeroObjectSizeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GcPressureEffect(1_000_000L, 0, false, Duration.ofSeconds(5)));
  }

  @Test
  void gcPressureNullDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, null));
  }

  @Test
  void gcPressureZeroDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ZERO));
  }

  @Test
  void gcPressureNegativeDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChaosEffect.GcPressureEffect(
                1_000_000L, 1024, false, Duration.ofMillis(-1)));
  }

  @Test
  void gcPressureFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.GcPressureEffect.class,
        ChaosEffect.gcPressure(1_000_000L, Duration.ofSeconds(2)));
  }

  // ── FinalizerBacklogEffect ────────────────────────────────────────────────

  @Test
  void finalizerBacklogValidConstruction() {
    assertDoesNotThrow(
        () -> new ChaosEffect.FinalizerBacklogEffect(100, Duration.ofMillis(500)));
  }

  @Test
  void finalizerBacklogZeroDelayIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.FinalizerBacklogEffect(10, Duration.ZERO));
  }

  @Test
  void finalizerBacklogZeroCountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.FinalizerBacklogEffect(0, Duration.ofMillis(100)));
  }

  @Test
  void finalizerBacklogNullDelayThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.FinalizerBacklogEffect(10, null));
  }

  @Test
  void finalizerBacklogNegativeDelayThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(-1)));
  }

  @Test
  void finalizerBacklogFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.FinalizerBacklogEffect.class,
        ChaosEffect.finalizerBacklog(10, Duration.ofMillis(100)));
  }

  // ── DeadlockEffect ────────────────────────────────────────────────────────

  @Test
  void deadlockValidConstruction() {
    assertDoesNotThrow(
        () -> new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(500)));
  }

  @Test
  void deadlockMoreThanTwoParticipantsIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.DeadlockEffect(5, Duration.ofMillis(100)));
  }

  @Test
  void deadlockZeroDelayIsValid() {
    assertDoesNotThrow(() -> new ChaosEffect.DeadlockEffect(2, Duration.ZERO));
  }

  @Test
  void deadlockOneParticipantThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DeadlockEffect(1, Duration.ofMillis(100)));
  }

  @Test
  void deadlockZeroParticipantsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DeadlockEffect(0, Duration.ofMillis(100)));
  }

  @Test
  void deadlockNullDelayThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.DeadlockEffect(2, null));
  }

  @Test
  void deadlockNegativeDelayThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(-1)));
  }

  @Test
  void deadlockFactoryProducesCorrectInstance() {
    assertInstanceOf(ChaosEffect.DeadlockEffect.class, ChaosEffect.deadlock(3));
  }

  // ── ThreadLeakEffect ──────────────────────────────────────────────────────

  @Test
  void threadLeakValidConstruction() {
    assertDoesNotThrow(
        () -> new ChaosEffect.ThreadLeakEffect(5, "leaked-", true, null));
  }

  @Test
  void threadLeakZeroCountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ThreadLeakEffect(0, "leaked-", true, null));
  }

  @Test
  void threadLeakNegativeCountThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ThreadLeakEffect(-1, "leaked-", true, null));
  }

  @Test
  void threadLeakNullPrefixThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ThreadLeakEffect(1, null, true, null));
  }

  @Test
  void threadLeakBlankPrefixThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.ThreadLeakEffect(1, "   ", true, null));
  }

  @Test
  void threadLeakFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.ThreadLeakEffect.class, ChaosEffect.threadLeak(2, "leak-", true));
  }

  // ── ThreadLocalLeakEffect ─────────────────────────────────────────────────

  @Test
  void threadLocalLeakValidConstruction() {
    assertDoesNotThrow(() -> new ChaosEffect.ThreadLocalLeakEffect(10, 1024));
  }

  @Test
  void threadLocalLeakZeroEntriesThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.ThreadLocalLeakEffect(0, 1024));
  }

  @Test
  void threadLocalLeakNegativeEntriesThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.ThreadLocalLeakEffect(-1, 1024));
  }

  @Test
  void threadLocalLeakZeroValueSizeThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.ThreadLocalLeakEffect(10, 0));
  }

  @Test
  void threadLocalLeakNegativeValueSizeThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ChaosEffect.ThreadLocalLeakEffect(10, -1));
  }

  @Test
  void threadLocalLeakFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.ThreadLocalLeakEffect.class, ChaosEffect.threadLocalLeak(5, 512));
  }

  // ── MonitorContentionEffect ───────────────────────────────────────────────

  @Test
  void monitorContentionValidConstruction() {
    assertDoesNotThrow(
        () ->
            new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 3, false));
  }

  @Test
  void monitorContentionNullDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MonitorContentionEffect(null, 3, false));
  }

  @Test
  void monitorContentionZeroDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MonitorContentionEffect(Duration.ZERO, 3, false));
  }

  @Test
  void monitorContentionNegativeDurationThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(-1), 3, false));
  }

  @Test
  void monitorContentionOneThreadThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 1, false));
  }

  @Test
  void monitorContentionZeroThreadsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 0, false));
  }

  @Test
  void monitorContentionFactoryProducesCorrectInstance() {
    assertInstanceOf(
        ChaosEffect.MonitorContentionEffect.class,
        ChaosEffect.monitorContention(Duration.ofMillis(10), 2));
  }
}
