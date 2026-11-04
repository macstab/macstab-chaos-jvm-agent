package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The effect factories' results and the validation edges of the effects they build. */
@DisplayName("ChaosEffect factories and bounds")
class ChaosEffectFactoriesTest {

  @Nested
  @DisplayName("network and file effects")
  class NetworkAndFile {

    @Test
    @DisplayName("errno carries the code; a missing code is rejected")
    void errno() {
      assertThat(ChaosEffect.errno(ChaosEffect.Errno.EPIPE).errno())
          .isEqualTo(ChaosEffect.Errno.EPIPE);
      assertThatThrownBy(() -> new ChaosEffect.ErrnoEffect(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("errno must not be null");
    }

    @Test
    @DisplayName("an errno's code is the host OS's, its reason the libc text")
    void errnoCodeFollowsTheHost() {
      final boolean macos =
          System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

      assertThat(ChaosEffect.Errno.ECONNREFUSED.code()).isEqualTo(macos ? 61 : 111);
      assertThat(ChaosEffect.Errno.ENOSPC.reason()).isEqualTo("No space left on device");
    }

    @Test
    @DisplayName("the parameterless effects are built by their factories")
    void parameterless() {
      assertThat(ChaosEffect.connectionReset()).isEqualTo(new ChaosEffect.ConnectionResetEffect());
      assertThat(ChaosEffect.corruptRead()).isEqualTo(new ChaosEffect.CorruptReadEffect());
      assertThat(ChaosEffect.timeout()).isEqualTo(new ChaosEffect.TimeoutEffect());
      assertThat(ChaosEffect.tornWrite()).isEqualTo(new ChaosEffect.TornWriteEffect());
      assertThat(ChaosEffect.suppress()).isEqualTo(new ChaosEffect.SuppressEffect());
      assertThat(ChaosEffect.spuriousWakeup()).isEqualTo(new ChaosEffect.SpuriousWakeupEffect());
    }

    @Test
    @DisplayName("trickle needs at least one byte per read")
    void trickle() {
      assertThat(ChaosEffect.trickle(1).maxBytes()).isEqualTo(1);
      assertThatThrownBy(() -> ChaosEffect.trickle(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("maxBytes must be >= 1, got 0");
    }

    @Test
    @DisplayName("redirect keeps host and port; the one-argument form keeps the port")
    void redirect() {
      assertThat(ChaosEffect.redirect("h", 1)).isEqualTo(new ChaosEffect.RedirectEffect("h", 1));
      assertThat(ChaosEffect.redirect("h", 65_535).port()).isEqualTo(65_535);
      assertThat(ChaosEffect.redirect("h").port()).isNull();
      assertThat(ChaosEffect.redirect("h").host()).isEqualTo("h");
    }

    @Test
    @DisplayName("redirect rejects a blank host and a port outside 1..65535")
    void redirectRejects() {
      assertThatThrownBy(() -> ChaosEffect.redirect(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("host must be non-blank");
      assertThatThrownBy(() -> ChaosEffect.redirect(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("host must be non-blank");
      assertThatThrownBy(() -> ChaosEffect.redirect("h", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("port must be in 1..65535, got 0");
      assertThatThrownBy(() -> ChaosEffect.redirect("h", 65_536))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("port must be in 1..65535, got 65536");
    }

    @Test
    @DisplayName("dnsRewrite keeps the host and rejects a blank one")
    void dnsRewrite() {
      assertThat(ChaosEffect.dnsRewrite("other.local").host()).isEqualTo("other.local");
      assertThatThrownBy(() -> ChaosEffect.dnsRewrite(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("host must be non-blank");
      assertThatThrownBy(() -> ChaosEffect.dnsRewrite(""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("host must be non-blank");
    }
  }

  @Nested
  @DisplayName("DnsAnswerEffect")
  class DnsAnswer {

    @Test
    @DisplayName("each factory sets exactly its own field")
    void factories() {
      assertThat(ChaosEffect.dnsOverride("10.0.0.1", "::1"))
          .isEqualTo(
              new ChaosEffect.DnsAnswerEffect(List.of("10.0.0.1", "::1"), null, false, null));
      assertThat(ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6))
          .isEqualTo(
              new ChaosEffect.DnsAnswerEffect(null, ChaosEffect.AddressFamily.IPV6, false, null));
      assertThat(ChaosEffect.dnsShuffle())
          .isEqualTo(new ChaosEffect.DnsAnswerEffect(null, null, true, null));
      assertThat(ChaosEffect.dnsLimit(1))
          .isEqualTo(new ChaosEffect.DnsAnswerEffect(null, null, false, 1));
    }

    @Test
    @DisplayName("the override list is an immutable copy")
    void overrideIsCopied() {
      final List<String> addresses = new ArrayList<>(List.of("10.0.0.1"));
      final ChaosEffect.DnsAnswerEffect effect =
          new ChaosEffect.DnsAnswerEffect(addresses, null, false, null);
      addresses.add("10.0.0.2");

      assertThat(effect.override()).containsExactly("10.0.0.1");
    }

    @Test
    @DisplayName("an empty override, a non-literal address and a limit below one are rejected")
    void rejects() {
      assertThatThrownBy(() -> new ChaosEffect.DnsAnswerEffect(List.of(), null, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("override must name at least one address");
      assertThatThrownBy(() -> ChaosEffect.dnsOverride("db.local"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not an IPv4 or IPv6 literal");
      assertThatThrownBy(() -> ChaosEffect.dnsLimit(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("limit must be >= 1, got 0");
    }

    @Test
    @DisplayName("an answer effect that changes nothing is rejected")
    void needsSomething() {
      assertThatThrownBy(() -> new ChaosEffect.DnsAnswerEffect(null, null, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("DnsAnswerEffect needs override, family, shuffle or limit");
    }
  }

  @Nested
  @DisplayName("exception injection")
  class ExceptionInjection {

    @Test
    @DisplayName("a malformed class name is rejected even inside an allowed package")
    void malformedName() {
      assertThatThrownBy(
              () -> new ChaosEffect.ExceptionInjectionEffect("java.lang..Boom", "m", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("exceptionClassName is not a valid binary class name: java.lang..Boom");
    }

    @Test
    @DisplayName("a class outside the allowed packages is rejected")
    void disallowedPackage() {
      assertThatThrownBy(
              () -> new ChaosEffect.ExceptionInjectionEffect("org.example.Boom", "m", false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("exceptionClassName is not in an allowed package");
    }

    @Test
    @DisplayName("a class in any allowed package is accepted")
    void allowedPackages() {
      assertThatCode(
              () -> {
                new ChaosEffect.ExceptionInjectionEffect("java.io.IOException", "m", true);
                new ChaosEffect.ExceptionInjectionEffect("javax.net.ssl.SSLException", "m", true);
                new ChaosEffect.ExceptionInjectionEffect("jakarta.ws.Boom", "m", true);
                new ChaosEffect.ExceptionInjectionEffect("com.macstab.chaos.jvm.X", "m", true);
              })
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("time effects")
  class Time {

    @Test
    @DisplayName("a delay of exactly the maximum is accepted")
    void delayAtMaximum() {
      assertThat(ChaosEffect.delay(ChaosEffect.DelayEffect.MAX_DELAY).maxDelay())
          .isEqualTo(Duration.ofDays(30));
      assertThatThrownBy(() -> ChaosEffect.delay(Duration.ofDays(30).plusNanos(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REGRESS takes a positive skew and rejects a negative one")
    void regress() {
      assertThat(
              ChaosEffect.skewClock(Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.REGRESS)
                  .skewAmount())
          .isEqualTo(Duration.ofSeconds(5));
      assertThatThrownBy(
              () ->
                  ChaosEffect.skewClock(Duration.ofSeconds(-5), ChaosEffect.ClockSkewMode.REGRESS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("REGRESS");
      assertThat(
              ChaosEffect.skewClock(Duration.ofSeconds(-5), ChaosEffect.ClockSkewMode.FIXED)
                  .skewAmount())
          .isEqualTo(Duration.ofSeconds(-5));
    }
  }

  @Nested
  @DisplayName("resource pressure effects")
  class Pressure {

    @Test
    @DisplayName("heap pressure accepts exactly the maximum total and chunk size")
    void heapPressureMaxima() {
      assertThatCode(
              () ->
                  ChaosEffect.heapPressure(
                      ChaosEffect.HeapPressureEffect.MAX_BYTES,
                      ChaosEffect.HeapPressureEffect.MAX_CHUNK_SIZE_BYTES))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("direct buffer pressure: one buffer may span the total, a zero total is named")
    void directBufferPressure() {
      assertThatCode(() -> ChaosEffect.directBufferPressure(4096L, 4096))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> ChaosEffect.directBufferPressure(0L, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("totalBytes must be > 0");
      assertThatThrownBy(() -> ChaosEffect.directBufferPressure(4096L, 4097))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("bufferSizeBytes must be <= totalBytes");
    }

    @Test
    @DisplayName("the count limits of the stressors are inclusive")
    void inclusiveMaxima() {
      assertThatCode(
              () -> {
                ChaosEffect.finalizerBacklog(
                    ChaosEffect.FinalizerBacklogEffect.MAX_OBJECT_COUNT, Duration.ZERO);
                ChaosEffect.deadlock(ChaosEffect.DeadlockEffect.MAX_PARTICIPANT_COUNT);
                ChaosEffect.monitorContention(
                    Duration.ofMillis(1),
                    ChaosEffect.MonitorContentionEffect.MAX_CONTENDING_THREAD_COUNT);
                new ChaosEffect.ThreadLeakEffect(
                    ChaosEffect.ThreadLeakEffect.MAX_THREAD_COUNT, "leak-", true, null);
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a thread leak's lifespan must be positive when set")
    void threadLeakLifespan() {
      assertThat(
              new ChaosEffect.ThreadLeakEffect(1, "leak-", true, Duration.ofMillis(1)).lifespan())
          .isEqualTo(Duration.ofMillis(1));
      assertThatThrownBy(() -> new ChaosEffect.ThreadLeakEffect(1, "leak-", true, Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("lifespan must be positive when set");
      assertThatThrownBy(
              () -> new ChaosEffect.ThreadLeakEffect(1, "leak-", true, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("lifespan must be positive when set");
    }

    @Test
    @DisplayName("code cache pressure needs at least one class and one method each")
    void codeCachePressure() {
      assertThat(ChaosEffect.codeCachePressure(1, 1))
          .isEqualTo(new ChaosEffect.CodeCachePressureEffect(1, 1));
      assertThatThrownBy(() -> ChaosEffect.codeCachePressure(0, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("classCount must be > 0");
      assertThatThrownBy(() -> ChaosEffect.codeCachePressure(1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("methodsPerClass must be > 0");
    }

    @Test
    @DisplayName("string intern pressure needs at least one string of one byte")
    void stringInternPressure() {
      assertThat(ChaosEffect.stringInternPressure(1, 1))
          .isEqualTo(new ChaosEffect.StringInternPressureEffect(1, 1));
      assertThatThrownBy(() -> ChaosEffect.stringInternPressure(0, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("internCount must be > 0");
      assertThatThrownBy(() -> ChaosEffect.stringInternPressure(1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("stringLengthBytes must be > 0");
    }

    @Test
    @DisplayName("a safepoint storm needs a positive interval and retransforms nothing by default")
    void safepointStorm() {
      assertThat(ChaosEffect.safepointStorm(Duration.ofMillis(5)))
          .isEqualTo(new ChaosEffect.SafepointStormEffect(Duration.ofMillis(5), 0));
      for (final Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
        assertThatThrownBy(() -> ChaosEffect.safepointStorm(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("gcInterval must be positive");
      }
      assertThatThrownBy(() -> new ChaosEffect.SafepointStormEffect(Duration.ofMillis(5), -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("retransformClassCount must be >= 0");
    }

    @Test
    @DisplayName("a reference queue flood needs one reference and a positive interval")
    void referenceQueueFlood() {
      assertThat(ChaosEffect.referenceQueueFlood(1, Duration.ofMillis(1)))
          .isEqualTo(new ChaosEffect.ReferenceQueueFloodEffect(1, Duration.ofMillis(1)));
      assertThatThrownBy(() -> ChaosEffect.referenceQueueFlood(0, Duration.ofMillis(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("referenceCount must be > 0");
      for (final Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
        assertThatThrownBy(() -> ChaosEffect.referenceQueueFlood(1, bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("floodInterval must be positive");
      }
    }
  }
}
