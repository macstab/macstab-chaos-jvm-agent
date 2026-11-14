package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Probe main for client-library hooks the other probes do not drive through a real call: the Apache
 * HttpClient 4 and 5 rejects, the AWS SDK's asynchronous client and a Netty read. Runs only in
 * child JVMs whose classpath carries those libraries.
 */
public final class ClientRealCallProbeMain {

  private ClientRealCallProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "apache-reject" -> DefectProbeMain.withHttpServer(ClientRealCallProbeMain::apacheReject);
      case "aws-async" -> awsAsync();
      case "netty-read" -> nettyRead();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  private static void print(final String row, final @Nullable Throwable failure) {
    System.out.println(row + ".type=" + (failure == null ? "none" : failure.getClass().getName()));
    if (failure != null) {
      System.out.println(row + ".message=" + failure.getMessage());
    }
  }

  /** A reject of HTTP_CLIENT_SEND under Apache HttpClient 5 and 4, against a local server. */
  private static void apacheReject(final String url) throws Exception {
    final ChaosSelector send = ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND));
    try (org.apache.hc.client5.http.impl.classic.CloseableHttpClient client =
        org.apache.hc.client5.http.impl.classic.HttpClients.createDefault()) {
      print(
          "hc5",
          RealCallRows.during(
              "hc5-reject",
              send,
              ChaosEffect.reject("chaos"),
              () ->
                  client.execute(
                      new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                      response -> response.getCode())));
      System.out.println(
          "hc5.after="
              + client.execute(
                  new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                  response -> response.getCode()));
    }
    try (org.apache.http.impl.client.CloseableHttpClient client =
        org.apache.http.impl.client.HttpClients.createDefault()) {
      print(
          "hc4",
          RealCallRows.during(
              "hc4-reject",
              send,
              ChaosEffect.reject("chaos"),
              () -> client.execute(new org.apache.http.client.methods.HttpGet(url)).close()));
      try (org.apache.http.client.methods.CloseableHttpResponse response =
          client.execute(new org.apache.http.client.methods.HttpGet(url))) {
        System.out.println("hc4.after=" + response.getStatusLine().getStatusCode());
      }
    }
  }

  /** An asynchronous HTTP client that fails every request; a rejected call never reaches it. */
  private static final class FailingAsyncHttpClient
      implements software.amazon.awssdk.http.async.SdkAsyncHttpClient {
    final AtomicLong requests = new AtomicLong();

    @Override
    public CompletableFuture<Void> execute(
        final software.amazon.awssdk.http.async.AsyncExecuteRequest request) {
      requests.incrementAndGet();
      return CompletableFuture.failedFuture(new java.io.IOException("no network in the probe"));
    }

    @Override
    public void close() {}
  }

  /**
   * {@code S3AsyncClient.listBuckets()} under an {@code AWS_SDK_CALL} reject: the future fails with
   * {@code SdkClientException} and no request reaches the HTTP client; without the scenario the
   * request does.
   */
  private static void awsAsync() throws Exception {
    final FailingAsyncHttpClient http = new FailingAsyncHttpClient();
    try (software.amazon.awssdk.services.s3.S3AsyncClient s3 =
        software.amazon.awssdk.services.s3.S3AsyncClient.builder()
            .region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .endpointOverride(URI.create("http://127.0.0.1:1"))
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("a", "b")))
            .httpClient(http)
            .build()) {
      try (ChaosActivationHandle reject =
          RealCallRows.activate(
              "aws-async-reject",
              ChaosSelector.awsSdk(NamePattern.exact("s3"), NamePattern.exact("ListBuckets")),
              ChaosEffect.reject("chaos"))) {
        final CompletableFuture<?> call = s3.listBuckets();
        System.out.println("rejected.returnedFuture=true");
        print("rejected", cause(call, 10));
        System.out.println("rejected.requests=" + http.requests.get());
        System.out.println("rejected.applied=" + reject.appliedCount());
      }
      // The probe's HTTP client fails its future without answering the SDK's handler, so the
      // call does not end; that the request reached the client is what counts.
      cause(s3.listBuckets(), 1);
      System.out.println("plain.requests=" + http.requests.get());
    }
  }

  private static @Nullable Throwable cause(final CompletableFuture<?> call, final long seconds) {
    try {
      call.get(seconds, TimeUnit.SECONDS);
      return null;
    } catch (final java.util.concurrent.ExecutionException failed) {
      return failed.getCause();
    } catch (final Exception other) {
      return other;
    }
  }

  /**
   * A 300 ms {@code NETTY_CHANNEL_READ} delay on a Netty NIO client: the bytes the peer sends reach
   * the handler after the delay. Prints the milliseconds from the peer's write to the handler's
   * read and the applied count.
   */
  private static void nettyRead() throws Exception {
    final io.netty.channel.EventLoopGroup group =
        new io.netty.channel.MultiThreadIoEventLoopGroup(
            1, io.netty.channel.nio.NioIoHandler.newFactory());
    final CountDownLatch read = new CountDownLatch(1);
    final AtomicLong readAt = new AtomicLong();
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      final io.netty.channel.Channel channel =
          new io.netty.bootstrap.Bootstrap()
              .group(group)
              .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
              .handler(
                  new io.netty.channel.ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(
                        final io.netty.channel.ChannelHandlerContext context,
                        final Object message) {
                      readAt.set(System.nanoTime());
                      io.netty.util.ReferenceCountUtil.release(message);
                      read.countDown();
                    }
                  })
              .connect("127.0.0.1", server.getLocalPort())
              .sync()
              .channel();
      try (Socket accepted = server.accept();
          ChaosActivationHandle delay =
              RealCallRows.activate(
                  "netty-read-delay",
                  new ChaosSelector.NettySelector(
                      Set.of(OperationType.NETTY_CHANNEL_READ), null, null),
                  ChaosEffect.delay(Duration.ofMillis(300)))) {
        final OutputStream out = accepted.getOutputStream();
        final long writtenAt = System.nanoTime();
        out.write(new byte[] {1, 2, 3});
        out.flush();
        System.out.println("read.done=" + read.await(5, TimeUnit.SECONDS));
        System.out.println(
            "read.millis=" + TimeUnit.NANOSECONDS.toMillis(readAt.get() - writtenAt));
        System.out.println("read.applied=" + delay.appliedCount());
      }
      channel.close().sync();
    } finally {
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }
}
