package com.macstab.chaos.jvm.startup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import java.time.Duration;
import java.time.Instant;

/**
 * Jackson mix-ins that give the API types their JSON shape: the {@code "type"} discriminator and
 * its names for selectors and effects, and the creators of {@link ActivationPolicy} and {@link
 * NamePattern}.
 *
 * <p>They live here, not as annotations on the API types, so {@code chaos-agent-api} has no
 * dependency at all. The agent jar relocates Jackson; annotations on the API types would have to be
 * relocated with it, and the API classes inside the agent jar would then differ from the ones in
 * the published API jar.
 */
final class ApiJsonMixins {

  private ApiJsonMixins() {}

  /**
   * Registers every mix-in on {@code builder}.
   *
   * @param builder the mapper builder
   * @return {@code builder}
   */
  static JsonMapper.Builder register(final JsonMapper.Builder builder) {
    return builder
        .addMixIn(ChaosSelector.class, SelectorMixin.class)
        .addMixIn(ChaosEffect.class, EffectMixin.class)
        .addMixIn(ActivationPolicy.class, ActivationPolicyMixin.class)
        .addMixIn(NamePattern.class, NamePatternMixin.class);
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ChaosSelector.ThreadSelector.class, name = "thread"),
    @JsonSubTypes.Type(value = ChaosSelector.ExecutorSelector.class, name = "executor"),
    @JsonSubTypes.Type(value = ChaosSelector.QueueSelector.class, name = "queue"),
    @JsonSubTypes.Type(value = ChaosSelector.AsyncSelector.class, name = "async"),
    @JsonSubTypes.Type(value = ChaosSelector.SchedulingSelector.class, name = "scheduling"),
    @JsonSubTypes.Type(value = ChaosSelector.ShutdownSelector.class, name = "shutdown"),
    @JsonSubTypes.Type(value = ChaosSelector.ClassLoadingSelector.class, name = "classLoading"),
    @JsonSubTypes.Type(value = ChaosSelector.MethodSelector.class, name = "method"),
    @JsonSubTypes.Type(value = ChaosSelector.MonitorSelector.class, name = "monitor"),
    @JsonSubTypes.Type(value = ChaosSelector.JvmRuntimeSelector.class, name = "jvmRuntime"),
    @JsonSubTypes.Type(value = ChaosSelector.NioSelector.class, name = "nio"),
    @JsonSubTypes.Type(value = ChaosSelector.NetworkSelector.class, name = "network"),
    @JsonSubTypes.Type(value = ChaosSelector.ThreadLocalSelector.class, name = "threadLocal"),
    @JsonSubTypes.Type(value = ChaosSelector.HttpClientSelector.class, name = "httpClient"),
    @JsonSubTypes.Type(value = ChaosSelector.JdbcSelector.class, name = "jdbc"),
    @JsonSubTypes.Type(value = ChaosSelector.DnsSelector.class, name = "dns"),
    @JsonSubTypes.Type(value = ChaosSelector.SslSelector.class, name = "ssl"),
    @JsonSubTypes.Type(value = ChaosSelector.FileIoSelector.class, name = "fileIo"),
    @JsonSubTypes.Type(value = ChaosSelector.MessagingSelector.class, name = "messaging"),
    @JsonSubTypes.Type(value = ChaosSelector.RpcSelector.class, name = "rpc"),
    @JsonSubTypes.Type(value = ChaosSelector.RedisSelector.class, name = "redis"),
    @JsonSubTypes.Type(value = ChaosSelector.AwsSdkSelector.class, name = "awsSdk"),
    @JsonSubTypes.Type(value = ChaosSelector.NettySelector.class, name = "netty"),
    @JsonSubTypes.Type(value = ChaosSelector.ProcessSelector.class, name = "process"),
    @JsonSubTypes.Type(value = ChaosSelector.StressSelector.class, name = "stress"),
  })
  private interface SelectorMixin {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
  @JsonSubTypes({
    // ── Interceptor effects ────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = ChaosEffect.DelayEffect.class, name = "delay"),
    @JsonSubTypes.Type(value = ChaosEffect.GateEffect.class, name = "gate"),
    @JsonSubTypes.Type(value = ChaosEffect.RejectEffect.class, name = "reject"),
    @JsonSubTypes.Type(value = ChaosEffect.ErrnoEffect.class, name = "errno"),
    @JsonSubTypes.Type(value = ChaosEffect.ConnectionResetEffect.class, name = "connectionReset"),
    @JsonSubTypes.Type(value = ChaosEffect.TrickleEffect.class, name = "trickle"),
    @JsonSubTypes.Type(value = ChaosEffect.CorruptReadEffect.class, name = "corruptRead"),
    @JsonSubTypes.Type(value = ChaosEffect.TimeoutEffect.class, name = "timeout"),
    @JsonSubTypes.Type(value = ChaosEffect.TornWriteEffect.class, name = "tornWrite"),
    @JsonSubTypes.Type(value = ChaosEffect.DnsAnswerEffect.class, name = "dnsAnswer"),
    @JsonSubTypes.Type(value = ChaosEffect.DnsRewriteEffect.class, name = "dnsRewrite"),
    @JsonSubTypes.Type(value = ChaosEffect.SuppressEffect.class, name = "suppress"),
    @JsonSubTypes.Type(
        value = ChaosEffect.ExceptionalCompletionEffect.class,
        name = "exceptionalCompletion"),
    @JsonSubTypes.Type(
        value = ChaosEffect.ExceptionInjectionEffect.class,
        name = "exceptionInjection"),
    @JsonSubTypes.Type(
        value = ChaosEffect.ReturnValueCorruptionEffect.class,
        name = "returnValueCorruption"),
    @JsonSubTypes.Type(value = ChaosEffect.ClockSkewEffect.class, name = "clockSkew"),

    // ── Stressor effects ───────────────────────────────────────────────────────
    @JsonSubTypes.Type(value = ChaosEffect.HeapPressureEffect.class, name = "heapPressure"),
    @JsonSubTypes.Type(value = ChaosEffect.KeepAliveEffect.class, name = "keepAlive"),
    @JsonSubTypes.Type(
        value = ChaosEffect.MetaspacePressureEffect.class,
        name = "metaspacePressure"),
    @JsonSubTypes.Type(
        value = ChaosEffect.DirectBufferPressureEffect.class,
        name = "directBufferPressure"),
    @JsonSubTypes.Type(value = ChaosEffect.GcPressureEffect.class, name = "gcPressure"),
    @JsonSubTypes.Type(value = ChaosEffect.FinalizerBacklogEffect.class, name = "finalizerBacklog"),
    @JsonSubTypes.Type(value = ChaosEffect.DeadlockEffect.class, name = "deadlock"),
    @JsonSubTypes.Type(value = ChaosEffect.ThreadLeakEffect.class, name = "threadLeak"),
    @JsonSubTypes.Type(value = ChaosEffect.ThreadLocalLeakEffect.class, name = "threadLocalLeak"),
    @JsonSubTypes.Type(
        value = ChaosEffect.MonitorContentionEffect.class,
        name = "monitorContention"),
    @JsonSubTypes.Type(value = ChaosEffect.SpuriousWakeupEffect.class, name = "spuriousWakeup"),
    @JsonSubTypes.Type(
        value = ChaosEffect.CodeCachePressureEffect.class,
        name = "codeCachePressure"),
    @JsonSubTypes.Type(value = ChaosEffect.SafepointStormEffect.class, name = "safepointStorm"),
    @JsonSubTypes.Type(
        value = ChaosEffect.StringInternPressureEffect.class,
        name = "stringInternPressure"),
    @JsonSubTypes.Type(
        value = ChaosEffect.ReferenceQueueFloodEffect.class,
        name = "referenceQueueFlood"),
    @JsonSubTypes.Type(
        value = ChaosEffect.VirtualThreadCarrierPinningEffect.class,
        name = "virtualThreadCarrierPinning"),
  })
  private interface EffectMixin {}

  private abstract static class ActivationPolicyMixin {
    @JsonCreator
    static ActivationPolicy fromJson(
        @JsonProperty("startMode") final ActivationPolicy.StartMode startMode,
        @JsonProperty("probability") final Double probability,
        @JsonProperty("activateAfterMatches") final long activateAfterMatches,
        @JsonProperty("maxApplications") final Long maxApplications,
        @JsonProperty("activeFor") final Duration activeFor,
        @JsonProperty("rateLimit") final ActivationPolicy.RateLimit rateLimit,
        @JsonProperty("randomSeed") final Long randomSeed,
        @JsonProperty("allowDestructiveEffects") final boolean allowDestructiveEffects,
        @JsonProperty("activeFrom") final Instant activeFrom,
        @JsonProperty("activeUntil") final Instant activeUntil,
        @JsonProperty("replayMatches") final java.util.List<Long> replayMatches) {
      throw new UnsupportedOperationException("mix-in");
    }
  }

  private abstract static class NamePatternMixin {
    @JsonCreator
    NamePatternMixin(
        @JsonProperty("mode") final NamePattern.MatchMode mode,
        @JsonProperty("value") final String value) {}
  }
}
