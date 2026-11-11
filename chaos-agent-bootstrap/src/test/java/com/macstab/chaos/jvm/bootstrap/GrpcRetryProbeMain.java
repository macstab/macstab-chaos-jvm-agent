package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import java.util.List;
import java.util.Map;

/**
 * Probe main for a gRPC reject under a channel's retry policy. Runs only in child JVMs whose
 * classpath carries gRPC ({@code chaos.test.clientClasspath}).
 */
public final class GrpcRetryProbeMain {

  private GrpcRetryProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "retry-policy" -> retryPolicy();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A channel whose service config retries {@code UNAVAILABLE} up to three attempts, under a reject
   * with {@code maxApplications} 1: the first attempt is rejected, the retry reaches the server.
   * Prints {@code reply} (or {@code call.code}), {@code applied} and {@code received}.
   */
  private static void retryPolicy() throws Exception {
    final io.grpc.MethodDescriptor<String, String> say = ClientLibraryProbeMain.echoSay();
    final java.util.concurrent.atomic.AtomicInteger received =
        new java.util.concurrent.atomic.AtomicInteger();
    final io.grpc.Server server =
        io.grpc.inprocess.InProcessServerBuilder.forName("retry")
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    ClientLibraryProbeMain.echoService(say),
                    new io.grpc.ServerInterceptor() {
                      @Override
                      public <Q, A> io.grpc.ServerCall.Listener<Q> interceptCall(
                          final io.grpc.ServerCall<Q, A> call,
                          final io.grpc.Metadata headers,
                          final io.grpc.ServerCallHandler<Q, A> next) {
                        received.incrementAndGet();
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
    final Map<String, Object> retryPolicy =
        Map.of(
            "maxAttempts",
            3.0d,
            "initialBackoff",
            "0.01s",
            "maxBackoff",
            "0.05s",
            "backoffMultiplier",
            1.0d,
            "retryableStatusCodes",
            List.of("UNAVAILABLE"));
    final Map<String, Object> serviceConfig =
        Map.of(
            "methodConfig",
            List.of(
                Map.of(
                    "name", List.of(Map.of("service", "probe.Echo")), "retryPolicy", retryPolicy)));
    final io.grpc.ManagedChannel channel =
        io.grpc.inprocess.InProcessChannelBuilder.forName("retry")
            .defaultServiceConfig(serviceConfig)
            .enableRetry()
            .build();
    try (ChaosActivationHandle reject =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("grpc-retry")
                    .selector(ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")))
                    .effect(ChaosEffect.reject("grpc down"))
                    .activationPolicy(ActivationPolicy.count(1L))
                    .build())) {
      try {
        System.out.println(
            "reply="
                + io.grpc.stub.ClientCalls.blockingUnaryCall(
                    channel,
                    say,
                    io.grpc.CallOptions.DEFAULT.withDeadlineAfter(
                        5, java.util.concurrent.TimeUnit.SECONDS),
                    "hi"));
      } catch (final io.grpc.StatusRuntimeException failure) {
        System.out.println("call.code=" + failure.getStatus().getCode());
      }
      System.out.println("applied=" + reject.appliedCount());
      System.out.println("received=" + received.get());
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }
}
