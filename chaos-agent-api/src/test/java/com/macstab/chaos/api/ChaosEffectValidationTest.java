package com.macstab.chaos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosEffect")
class ChaosEffectValidationTest {

  @Nested
  @DisplayName("DelayEffect")
  class DelayEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), Duration.ofMillis(100)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero delay is valid")
    void zeroDelayIsValid() {
      assertThatCode(() -> new ChaosEffect.DelayEffect(Duration.ZERO, Duration.ZERO))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null min throws")
    void nullMinThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DelayEffect(null, Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null max throws")
    void nullMaxThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative min throws")
    void negativeMinThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.DelayEffect(Duration.ofMillis(-1), Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative max throws")
    void negativeMaxThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.DelayEffect(Duration.ofMillis(10), Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("max less than min throws")
    void maxLessThanMinThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.DelayEffect(Duration.ofMillis(200), Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fixed factory creates equal bounds")
    void fixedFactoryCreatesEqualBounds() {
      ChaosEffect.DelayEffect effect = ChaosEffect.delay(Duration.ofMillis(50));
      assertThat(effect.minDelay()).isEqualTo(Duration.ofMillis(50));
      assertThat(effect.maxDelay()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    @DisplayName("range factory creates correct bounds")
    void rangeFactoryCreatesCorrectBounds() {
      ChaosEffect.DelayEffect effect =
          ChaosEffect.delay(Duration.ofMillis(10), Duration.ofMillis(200));
      assertThat(effect.minDelay()).isEqualTo(Duration.ofMillis(10));
      assertThat(effect.maxDelay()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("factory produces DelayEffect instance")
    void factoryProducesDelayEffectInstance() {
      assertThat(ChaosEffect.delay(Duration.ofMillis(1)))
          .isInstanceOf(ChaosEffect.DelayEffect.class);
    }
  }

  @Nested
  @DisplayName("GateEffect")
  class GateEffectTests {

    @Test
    @DisplayName("null maxBlock is valid")
    void nullMaxBlockIsValid() {
      assertThatCode(() -> new ChaosEffect.GateEffect(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("positive maxBlock is valid")
    void positiveMaxBlockIsValid() {
      assertThatCode(() -> new ChaosEffect.GateEffect(Duration.ofSeconds(5)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero maxBlock throws")
    void zeroMaxBlockThrows() {
      assertThatThrownBy(() -> new ChaosEffect.GateEffect(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative maxBlock throws")
    void negativeMaxBlockThrows() {
      assertThatThrownBy(() -> new ChaosEffect.GateEffect(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces GateEffect instance")
    void factoryProducesGateEffectInstance() {
      assertThat(ChaosEffect.gate(null)).isInstanceOf(ChaosEffect.GateEffect.class);
      assertThat(ChaosEffect.gate(Duration.ofSeconds(10)))
          .isInstanceOf(ChaosEffect.GateEffect.class);
    }
  }

  @Nested
  @DisplayName("RejectEffect")
  class RejectEffectTests {

    @Test
    @DisplayName("valid message")
    void validMessage() {
      assertThatCode(() -> new ChaosEffect.RejectEffect("chaos rejection"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null message throws")
    void nullMessageThrows() {
      assertThatThrownBy(() -> new ChaosEffect.RejectEffect(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank message throws")
    void blankMessageThrows() {
      assertThatThrownBy(() -> new ChaosEffect.RejectEffect("   "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty message throws")
    void emptyMessageThrows() {
      assertThatThrownBy(() -> new ChaosEffect.RejectEffect(""))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces RejectEffect instance")
    void factoryProducesRejectEffectInstance() {
      assertThat(ChaosEffect.reject("msg")).isInstanceOf(ChaosEffect.RejectEffect.class);
    }
  }

  @Nested
  @DisplayName("SuppressEffect")
  class SuppressEffectTests {

    @Test
    @DisplayName("construction is valid")
    void constructionIsValid() {
      assertThatCode(ChaosEffect.SuppressEffect::new).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("factory produces SuppressEffect instance")
    void factoryProducesSuppressEffectInstance() {
      assertThat(ChaosEffect.suppress()).isInstanceOf(ChaosEffect.SuppressEffect.class);
    }
  }

  @Nested
  @DisplayName("ExceptionalCompletionEffect")
  class ExceptionalCompletionEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () ->
                  new ChaosEffect.ExceptionalCompletionEffect(
                      ChaosEffect.FailureKind.TIMEOUT, "simulated timeout"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null kind throws")
    void nullKindThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ExceptionalCompletionEffect(null, "msg"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null message throws")
    void nullMessageThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.ExceptionalCompletionEffect(ChaosEffect.FailureKind.IO, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank message throws")
    void blankMessageThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.ExceptionalCompletionEffect(
                      ChaosEffect.FailureKind.REJECTED, "   "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces ExceptionalCompletionEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.RUNTIME, "err"))
          .isInstanceOf(ChaosEffect.ExceptionalCompletionEffect.class);
    }
  }

  @Nested
  @DisplayName("ExceptionInjectionEffect")
  class ExceptionInjectionEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () ->
                  new ChaosEffect.ExceptionInjectionEffect(
                      "java.io.IOException", "simulated IO failure", true))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null class name throws")
    void nullClassNameThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ExceptionInjectionEffect(null, "msg", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank class name throws")
    void blankClassNameThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ExceptionInjectionEffect("   ", "msg", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid binary name throws")
    void invalidBinaryNameThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ExceptionInjectionEffect("123.Bad", "msg", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null message throws")
    void nullMessageThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", null, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank message throws")
    void blankMessageThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "", true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory creates with stack trace")
    void factoryCreatesWithStackTrace() {
      ChaosEffect.ExceptionInjectionEffect effect =
          ChaosEffect.injectException("java.lang.RuntimeException", "chaos");
      assertThat(effect.exceptionClassName()).isEqualTo("java.lang.RuntimeException");
      assertThat(effect.message()).isEqualTo("chaos");
      assertThat(effect).isInstanceOf(ChaosEffect.ExceptionInjectionEffect.class);
    }
  }

  @Nested
  @DisplayName("ReturnValueCorruptionEffect")
  class ReturnValueCorruptionEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () ->
                  new ChaosEffect.ReturnValueCorruptionEffect(ChaosEffect.ReturnValueStrategy.NULL))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null strategy throws")
    void nullStrategyThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ReturnValueCorruptionEffect(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("all strategies are valid")
    void allStrategiesAreValid() {
      for (ChaosEffect.ReturnValueStrategy strategy : ChaosEffect.ReturnValueStrategy.values()) {
        assertThatCode(() -> new ChaosEffect.ReturnValueCorruptionEffect(strategy))
            .doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("factory produces ReturnValueCorruptionEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO))
          .isInstanceOf(ChaosEffect.ReturnValueCorruptionEffect.class);
    }
  }

  @Nested
  @DisplayName("ClockSkewEffect")
  class ClockSkewEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () ->
                  new ChaosEffect.ClockSkewEffect(
                      Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("negative amount is valid")
    void negativeAmountIsValid() {
      assertThatCode(
              () ->
                  new ChaosEffect.ClockSkewEffect(
                      Duration.ofSeconds(-60), ChaosEffect.ClockSkewMode.DRIFT))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null amount throws")
    void nullAmountThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.ClockSkewEffect(null, ChaosEffect.ClockSkewMode.FIXED))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero amount throws")
    void zeroAmountThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.ClockSkewEffect(Duration.ZERO, ChaosEffect.ClockSkewMode.FIXED))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null mode throws")
    void nullModeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ClockSkewEffect(Duration.ofSeconds(1), null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces ClockSkewEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.skewClock(Duration.ofSeconds(10), ChaosEffect.ClockSkewMode.FREEZE))
          .isInstanceOf(ChaosEffect.ClockSkewEffect.class);
    }
  }

  @Nested
  @DisplayName("HeapPressureEffect")
  class HeapPressureEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.HeapPressureEffect(1024L * 1024, 4096))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero bytes throws")
    void zeroBytesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.HeapPressureEffect(0, 512))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative bytes throws")
    void negativeBytesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.HeapPressureEffect(-1, 512))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero chunk size throws")
    void zeroChunkSizeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.HeapPressureEffect(1024L, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative chunk size throws")
    void negativeChunkSizeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.HeapPressureEffect(1024L, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces HeapPressureEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.heapPressure(1024L * 1024, 4096))
          .isInstanceOf(ChaosEffect.HeapPressureEffect.class);
    }
  }

  @Nested
  @DisplayName("KeepAliveEffect")
  class KeepAliveEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () -> new ChaosEffect.KeepAliveEffect("chaos-thread", true, Duration.ofSeconds(1)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null thread name throws")
    void nullThreadNameThrows() {
      assertThatThrownBy(() -> new ChaosEffect.KeepAliveEffect(null, true, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank thread name throws")
    void blankThreadNameThrows() {
      assertThatThrownBy(() -> new ChaosEffect.KeepAliveEffect("  ", true, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null heartbeat throws")
    void nullHeartbeatThrows() {
      assertThatThrownBy(() -> new ChaosEffect.KeepAliveEffect("t", true, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero heartbeat throws")
    void zeroHeartbeatThrows() {
      assertThatThrownBy(() -> new ChaosEffect.KeepAliveEffect("t", true, Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative heartbeat throws")
    void negativeHeartbeatThrows() {
      assertThatThrownBy(() -> new ChaosEffect.KeepAliveEffect("t", true, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces KeepAliveEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.keepAlive("t", false, Duration.ofSeconds(1)))
          .isInstanceOf(ChaosEffect.KeepAliveEffect.class);
    }
  }

  @Nested
  @DisplayName("MetaspacePressureEffect")
  class MetaspacePressureEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.MetaspacePressureEffect(10, 5, true))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero fields per class is valid")
    void zeroFieldsPerClassIsValid() {
      assertThatCode(() -> new ChaosEffect.MetaspacePressureEffect(10, 0, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero count throws")
    void zeroCountThrows() {
      assertThatThrownBy(() -> new ChaosEffect.MetaspacePressureEffect(0, 5, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative count throws")
    void negativeCountThrows() {
      assertThatThrownBy(() -> new ChaosEffect.MetaspacePressureEffect(-1, 5, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative fields throws")
    void negativeFieldsThrows() {
      assertThatThrownBy(() -> new ChaosEffect.MetaspacePressureEffect(10, -1, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces MetaspacePressureEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.metaspacePressure(5, 2))
          .isInstanceOf(ChaosEffect.MetaspacePressureEffect.class);
    }
  }

  @Nested
  @DisplayName("DirectBufferPressureEffect")
  class DirectBufferPressureEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.DirectBufferPressureEffect(1024L, 512, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero total bytes throws")
    void zeroTotalBytesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DirectBufferPressureEffect(0, 512, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative total bytes throws")
    void negativeTotalBytesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DirectBufferPressureEffect(-1, 512, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero buffer size throws")
    void zeroBufferSizeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DirectBufferPressureEffect(1024L, 0, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buffer size exceeds total throws")
    void bufferSizeExceedsTotalThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DirectBufferPressureEffect(100L, 200, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces DirectBufferPressureEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.directBufferPressure(1024L, 512))
          .isInstanceOf(ChaosEffect.DirectBufferPressureEffect.class);
    }
  }

  @Nested
  @DisplayName("GcPressureEffect")
  class GcPressureEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(
              () ->
                  new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofSeconds(5)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero rate throws")
    void zeroRateThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.GcPressureEffect(0, 1024, false, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative rate throws")
    void negativeRateThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.GcPressureEffect(-1, 1024, false, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero object size throws")
    void zeroObjectSizeThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.GcPressureEffect(1_000_000L, 0, false, Duration.ofSeconds(5)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null duration throws")
    void nullDurationThrows() {
      assertThatThrownBy(() -> new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero duration throws")
    void zeroDurationThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative duration throws")
    void negativeDurationThrows() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.GcPressureEffect(1_000_000L, 1024, false, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces GcPressureEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.gcPressure(1_000_000L, Duration.ofSeconds(2)))
          .isInstanceOf(ChaosEffect.GcPressureEffect.class);
    }
  }

  @Nested
  @DisplayName("FinalizerBacklogEffect")
  class FinalizerBacklogEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.FinalizerBacklogEffect(100, Duration.ofMillis(500)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero delay is valid")
    void zeroDelayIsValid() {
      assertThatCode(() -> new ChaosEffect.FinalizerBacklogEffect(10, Duration.ZERO))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero count throws")
    void zeroCountThrows() {
      assertThatThrownBy(() -> new ChaosEffect.FinalizerBacklogEffect(0, Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null delay throws")
    void nullDelayThrows() {
      assertThatThrownBy(() -> new ChaosEffect.FinalizerBacklogEffect(10, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative delay throws")
    void negativeDelayThrows() {
      assertThatThrownBy(() -> new ChaosEffect.FinalizerBacklogEffect(10, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces FinalizerBacklogEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.finalizerBacklog(10, Duration.ofMillis(100)))
          .isInstanceOf(ChaosEffect.FinalizerBacklogEffect.class);
    }
  }

  @Nested
  @DisplayName("DeadlockEffect")
  class DeadlockEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(500)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("more than two participants is valid")
    void moreThanTwoParticipantsIsValid() {
      assertThatCode(() -> new ChaosEffect.DeadlockEffect(5, Duration.ofMillis(100)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero delay is valid")
    void zeroDelayIsValid() {
      assertThatCode(() -> new ChaosEffect.DeadlockEffect(2, Duration.ZERO))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one participant throws")
    void oneParticipantThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DeadlockEffect(1, Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero participants throws")
    void zeroParticipantsThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DeadlockEffect(0, Duration.ofMillis(100)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null delay throws")
    void nullDelayThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DeadlockEffect(2, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative delay throws")
    void negativeDelayThrows() {
      assertThatThrownBy(() -> new ChaosEffect.DeadlockEffect(2, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces DeadlockEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.deadlock(3)).isInstanceOf(ChaosEffect.DeadlockEffect.class);
    }
  }

  @Nested
  @DisplayName("ThreadLeakEffect")
  class ThreadLeakEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.ThreadLeakEffect(5, "leaked-", true, null))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero count throws")
    void zeroCountThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLeakEffect(0, "leaked-", true, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative count throws")
    void negativeCountThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLeakEffect(-1, "leaked-", true, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null prefix throws")
    void nullPrefixThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLeakEffect(1, null, true, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank prefix throws")
    void blankPrefixThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLeakEffect(1, "   ", true, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces ThreadLeakEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.threadLeak(2, "leak-", true))
          .isInstanceOf(ChaosEffect.ThreadLeakEffect.class);
    }
  }

  @Nested
  @DisplayName("ThreadLocalLeakEffect")
  class ThreadLocalLeakEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.ThreadLocalLeakEffect(10, 1024))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero entries throws")
    void zeroEntriesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLocalLeakEffect(0, 1024))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative entries throws")
    void negativeEntriesThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLocalLeakEffect(-1, 1024))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero value size throws")
    void zeroValueSizeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLocalLeakEffect(10, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative value size throws")
    void negativeValueSizeThrows() {
      assertThatThrownBy(() -> new ChaosEffect.ThreadLocalLeakEffect(10, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces ThreadLocalLeakEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.threadLocalLeak(5, 512))
          .isInstanceOf(ChaosEffect.ThreadLocalLeakEffect.class);
    }
  }

  @Nested
  @DisplayName("MonitorContentionEffect")
  class MonitorContentionEffectTests {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 3, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null duration throws")
    void nullDurationThrows() {
      assertThatThrownBy(() -> new ChaosEffect.MonitorContentionEffect(null, 3, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero duration throws")
    void zeroDurationThrows() {
      assertThatThrownBy(() -> new ChaosEffect.MonitorContentionEffect(Duration.ZERO, 3, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative duration throws")
    void negativeDurationThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(-1), 3, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one thread throws")
    void oneThreadThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 1, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero threads throws")
    void zeroThreadsThrows() {
      assertThatThrownBy(
              () -> new ChaosEffect.MonitorContentionEffect(Duration.ofMillis(10), 0, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory produces MonitorContentionEffect instance")
    void factoryProducesCorrectInstance() {
      assertThat(ChaosEffect.monitorContention(Duration.ofMillis(10), 2))
          .isInstanceOf(ChaosEffect.MonitorContentionEffect.class);
    }
  }
}
