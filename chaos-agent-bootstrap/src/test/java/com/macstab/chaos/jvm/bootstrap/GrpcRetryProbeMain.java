package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probe main for a gRPC call under a channel's retry policy. Runs only in child JVMs whose
 * classpath carries gRPC ({@code chaos.test.clientClasspath}, {@code
 * chaos.test.legacyGrpcClasspath}).
 */
public final class GrpcRetryProbeMain {

  private GrpcRetryProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "retry-policy" -> retryPolicy();
      case "retry-delay" -> retryDelay();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * A channel whose service config retries {@code UNAVAILABLE} up to three attempts, under a reject
   * with {@code maxApplications} 1: the first attempt is rejected, the retry reaches the server.
   * Prints {@code newSubstreamArguments} (the parameter count of gRPC's per-attempt {@code
   * RetryStream.newSubstream}), {@code reply} (or {@code call.code}), {@code applied} and {@code
   * received}.
   */
  private static void retryPolicy() throws Exception {
    System.out.println("newSubstreamArguments=" + newSubstreamArguments());
    final io.grpc.MethodDescriptor<String, String> say = echoSay();
    final AtomicInteger received = new AtomicInteger();
    final io.grpc.Server server = countingServer("retry", say, received, 0);
    final io.grpc.ManagedChannel channel = retryingChannel("retry");
    try (ChaosActivationHandle reject =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("grpc-retry")
                    .selector(ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")))
                    .effect(ChaosEffect.reject("grpc down"))
                    .activationPolicy(ActivationPolicy.count(1L))
                    .build())) {
      call(channel, say);
      System.out.println("applied=" + reject.appliedCount());
      System.out.println("received=" + received.get());
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  /**
   * A 400 ms {@code RPC_CALL} delay on a channel that retries {@code UNAVAILABLE}, whose server
   * fails the first attempt for real: the delay is the call's, made once where the call is made,
   * not again for the retry on gRPC's own thread. Prints {@code reply} (or {@code call.code}),
   * {@code applied} (the delay's applications) and {@code received} (the attempts the server saw).
   */
  private static void retryDelay() throws Exception {
    final io.grpc.MethodDescriptor<String, String> say = echoSay();
    final AtomicInteger received = new AtomicInteger();
    final io.grpc.Server server = countingServer("retry-delay", say, received, 1);
    final io.grpc.ManagedChannel channel = retryingChannel("retry-delay");
    try (ChaosActivationHandle delay =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("grpc-delay")
                    .selector(ChaosSelector.rpc(NamePattern.exact("probe.Echo/Say")))
                    .effect(ChaosEffect.delay(Duration.ofMillis(400)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      call(channel, say);
      System.out.println("applied=" + delay.appliedCount());
      System.out.println("received=" + received.get());
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  /**
   * {@code probe.Echo/Say}, unary, strings as UTF-8. Built here, not taken from {@link
   * ClientLibraryProbeMain}: that class names the other client libraries, which are not on the
   * classpath of the older gRPC.
   */
  private static io.grpc.MethodDescriptor<String, String> echoSay() {
    final io.grpc.MethodDescriptor.Marshaller<String> marshaller =
        new io.grpc.MethodDescriptor.Marshaller<>() {
          @Override
          public java.io.InputStream stream(final String value) {
            return new java.io.ByteArrayInputStream(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }

          @Override
          public String parse(final java.io.InputStream stream) {
            try {
              return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (final java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          }
        };
    return io.grpc.MethodDescriptor.<String, String>newBuilder()
        .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
        .setFullMethodName("probe.Echo/Say")
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
  }

  /** The {@code probe.Echo} service: {@code Say} answers {@code "echo " + request}. */
  private static io.grpc.ServerServiceDefinition echoService(
      final io.grpc.MethodDescriptor<String, String> say) {
    return io.grpc.ServerServiceDefinition.builder("probe.Echo")
        .addMethod(
            say,
            io.grpc.stub.ServerCalls.asyncUnaryCall(
                (request, observer) -> {
                  observer.onNext("echo " + request);
                  observer.onCompleted();
                }))
        .build();
  }

  /** The parameter count of {@code RetryStream.newSubstream} in the gRPC on the classpath. */
  private static int newSubstreamArguments() throws ClassNotFoundException {
    for (final java.lang.reflect.Method method :
        Class.forName("io.grpc.internal.ManagedChannelImpl$ChannelStreamProvider$1RetryStream")
            .getDeclaredMethods()) {
      if (method.getName().equals("newSubstream")) {
        return method.getParameterCount();
      }
    }
    return -1;
  }

  /** Makes the unary call; prints {@code reply}, or {@code call.code} when it fails. */
  private static void call(
      final io.grpc.ManagedChannel channel, final io.grpc.MethodDescriptor<String, String> say) {
    try {
      System.out.println(
          "reply="
              + io.grpc.stub.ClientCalls.blockingUnaryCall(
                  channel,
                  say,
                  io.grpc.CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                  "hi"));
    } catch (final io.grpc.StatusRuntimeException failure) {
      System.out.println("call.code=" + failure.getStatus().getCode());
    }
  }

  /**
   * An in-process echo server that counts the attempts it receives in {@code received} and fails
   * the first {@code unavailable} of them with {@code UNAVAILABLE}.
   */
  private static io.grpc.Server countingServer(
      final String name,
      final io.grpc.MethodDescriptor<String, String> say,
      final AtomicInteger received,
      final int unavailable)
      throws java.io.IOException {
    return io.grpc.inprocess.InProcessServerBuilder.forName(name)
        .addService(
            io.grpc.ServerInterceptors.intercept(
                echoService(say),
                new io.grpc.ServerInterceptor() {
                  @Override
                  public <Q, A> io.grpc.ServerCall.Listener<Q> interceptCall(
                      final io.grpc.ServerCall<Q, A> call,
                      final io.grpc.Metadata headers,
                      final io.grpc.ServerCallHandler<Q, A> next) {
                    if (received.incrementAndGet() <= unavailable) {
                      call.close(io.grpc.Status.UNAVAILABLE, new io.grpc.Metadata());
                      return new io.grpc.ServerCall.Listener<>() {};
                    }
                    return next.startCall(call, headers);
                  }
                }))
        .build()
        .start();
  }

  /** An in-process channel whose service config retries {@code UNAVAILABLE}, 3 attempts at most. */
  private static io.grpc.ManagedChannel retryingChannel(final String name) {
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
    return io.grpc.inprocess.InProcessChannelBuilder.forName(name)
        .defaultServiceConfig(serviceConfig)
        .enableRetry()
        .build();
  }
}
