package com.macstab.chaos.jvm.instrumentation;

import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.advise;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The hook groups for NIO selectors and channels, sockets, datagrams and TLS handshakes: which
 * methods of which types receive which advice. {@link JdkInstrumentationInstaller.HookGroup} weaves
 * them on demand.
 */
final class NetworkHooks {
  private NetworkHooks() {}

  /**
   * NIO Selector, SocketChannel, and ServerSocketChannel instrumentation. Concrete-subtype-only
   * matchers avoid VerifyError on bridge / synthetic methods of abstract bases.
   */
  static AgentBuilder applyNioChannelTransformations(final AgentBuilder builder) {
    return builder
        // NIO Selector — target sun.nio.ch.SelectorImpl directly (it declares select(long),
        // select(), selectNow()) AND concrete AbstractSelector subtypes. Concrete-subtype
        // targeting alone misses the methods because KQueueSelectorImpl does not re-declare
        // them; they are inherited from SelectorImpl. Exclude abstract/interface/synthetic
        // matches on the subtype sweep to avoid VerifyError on bridge methods.
        .type(ElementMatchers.named("sun.nio.ch.SelectorImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            NioAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(ElementMatchers.takesArguments(0))))
                    // JDK 11+ select(Consumer) / select(Consumer, long) / selectNow(Consumer)
                    // reach lockAndDoSelect without calling the overloads above.
                    .visit(
                        advise(
                            NioAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectConsumerTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class, long.class))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class)))))
        .type(
            TypeMatchers.subTypeOf(java.nio.channels.spi.AbstractSelector.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            NioAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            NioAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            NioAdvice.SelectorWakeupAdvice.class,
                            ElementMatchers.named("wakeup")
                                .and(ElementMatchers.takesArguments(0)))))
        // NIO SocketChannel — the public class is abstract, so name() would only match the
        // abstract copy; instrument concrete subtypes (sun.nio.ch.SocketChannelImpl,
        // possibly vendor variants) where the actual I/O code lives. Exclude abstract /
        // synthetic matches to avoid VerifyError on bridge methods.
        .type(
            TypeMatchers.subTypeOf(java.nio.channels.SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            NioAdvice.NioChannelConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(ElementMatchers.takesArguments(java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            NioAdvice.NioChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            NioAdvice.NioChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            NioAdvice.NioChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            NioAdvice.NioChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class)))))
        // NIO ServerSocketChannel — same abstract-base issue as SocketChannel above.
        .type(
            TypeMatchers.subTypeOf(java.nio.channels.ServerSocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        NioAdvice.NioChannelAcceptAdvice.class,
                        ElementMatchers.named("accept").and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * Socket / ServerSocket / SocketInputStream / SocketOutputStream instrumentation. Only
   * instruments the 3-argument bulk variant of SocketInputStream.read(): the 0-arg single-byte
   * overload delegates internally to read(byte[], int, int), causing double-fire of
   * beforeSocketRead for each single-byte application call. Restricting to the final I/O dispatch
   * avoids double-counting rate-limit permits and probability draws.
   */
  static AgentBuilder applySocketAndStreamTransformations(final AgentBuilder builder) {
    return builder
        // Every bind of a socket or socket channel, explicit or implicit, funnels into this one.
        .type(ElementMatchers.named("sun.nio.ch.Net"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        SocketAdvice.SocketBindAdvice.class,
                        ElementMatchers.named("bind")
                            .and(
                                ElementMatchers.takesArguments(
                                    java.net.ProtocolFamily.class,
                                    java.io.FileDescriptor.class,
                                    java.net.InetAddress.class,
                                    int.class)))))
        .type(ElementMatchers.named("java.net.Socket"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            SocketAdvice.SocketConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.net.SocketAddress.class, int.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketCloseAdvice.class,
                            ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.net.ServerSocket"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        SocketAdvice.SocketAcceptAdvice.class,
                        ElementMatchers.named("implAccept")
                            .and(ElementMatchers.takesArguments(java.net.Socket.class)))))
        // Socket$SocketInputStream / Socket$SocketOutputStream are private inner classes loaded
        // as a side-effect of ByteBuddy retransforming java.net.Socket (JVMTI ClassFileLoadHook
        // is not re-entrant, so the inner-class loads escape the transformer). Target
        // sun.nio.ch.NioSocketImpl instead: it is the default SocketImpl since JDK 13, is a
        // public top-level class that retransforms cleanly, and is always reached by
        // Socket.getInputStream().read() / getOutputStream().write() on modern JDKs.
        .type(ElementMatchers.named("sun.nio.ch.NioSocketImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            SocketAdvice.SocketShutdownAdvice.class,
                            ElementMatchers.namedOneOf("shutdownInput", "shutdownOutput")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            SocketAdvice.SocketReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    // NioSocketImpl declares no write(int): single-byte writes of its output
                    // stream go through write(byte[], int, int) with len 1.
                    .visit(
                        advise(
                            SocketAdvice.SocketWriteBulkAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))));
  }

  /**
   * {@code SocketChannel} and {@code ServerSocketChannel} as sockets: connect, accept, reads and
   * writes (single, scattering, gathering) raise the {@code SOCKET_*} events with the channel's
   * peer and byte count, and a socket channel's close raises {@code SOCKET_CLOSE}. Matched on the
   * concrete subtypes, where the I/O lives; the close on {@code AbstractInterruptibleChannel}, the
   * only class declaring it, while the channel is still open.
   */
  static AgentBuilder applySocketChannelTransformations(final AgentBuilder builder) {
    return builder
        .type(
            TypeMatchers.subTypeOf(java.nio.channels.SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(ElementMatchers.takesArguments(java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelShutdownAdvice.class,
                            ElementMatchers.namedOneOf("shutdownInput", "shutdownOutput")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    // The funnels of the channel's adaptor (channel.socket()) and its streams.
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelBlockingConnectAdvice.class,
                            ElementMatchers.named("blockingConnect")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.net.SocketAddress.class, long.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelBlockingReadAdvice.class,
                            ElementMatchers.named("blockingRead")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class, long.class))))
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelBlockingWriteAdvice.class,
                            ElementMatchers.named("blockingWriteFully")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))))
        .type(
            TypeMatchers.subTypeOf(java.nio.channels.ServerSocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            SocketAdvice.SocketChannelAcceptAdvice.class,
                            ElementMatchers.named("accept").and(ElementMatchers.takesArguments(0))))
                    // The adaptor's accept with a timeout (ssc.socket().accept(), SO_TIMEOUT > 0).
                    .visit(
                        advise(
                            SocketAdvice.ServerSocketChannelBlockingAcceptAdvice.class,
                            ElementMatchers.named("blockingAccept")
                                .and(ElementMatchers.takesArguments(long.class)))))
        .type(ElementMatchers.named("java.nio.channels.spi.AbstractInterruptibleChannel"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        SocketAdvice.SocketChannelCloseAdvice.class,
                        ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * UDP: {@code DatagramChannelImpl}'s public sends and receives, and the funnels {@code
   * DatagramSocket} goes through ({@code blockingSend}, {@code blockingReceive}).
   */
  static AgentBuilder applyDatagramTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("sun.nio.ch.DatagramChannelImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            DatagramAdvice.DatagramSendAdvice.class,
                            ElementMatchers.named("send")
                                .and(ElementMatchers.isPublic())
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer.class, java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            DatagramAdvice.DatagramWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            DatagramAdvice.DatagramPacketSendAdvice.class,
                            ElementMatchers.named("blockingSend")
                                .and(
                                    ElementMatchers.takesArguments(java.net.DatagramPacket.class))))
                    .visit(
                        advise(
                            DatagramAdvice.DatagramReceiveAdvice.class,
                            ElementMatchers.named("receive")
                                .and(ElementMatchers.isPublic())
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            DatagramAdvice.DatagramReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            DatagramAdvice.DatagramPacketReceiveAdvice.class,
                            ElementMatchers.named("blockingReceive")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.net.DatagramPacket.class, long.class)))));
  }

  /**
   * SSL/TLS handshake interception on concrete SSLSocket / SSLEngine subtypes. SSLSocket and
   * SSLEngine themselves are abstract; concrete subclasses (SSLSocketImpl, SSLEngineImpl) override
   * startHandshake / beginHandshake, so advice on the abstract class alone would never fire.
   * Interface / synthetic filters exclude Mockito/CGLIB generated proxies that would otherwise trip
   * VerifyError during class transformation.
   */
  static AgentBuilder applySslHandshakeTransformations(final AgentBuilder builder) {
    AgentBuilder agentBuilder =
        builder
            .type(
                TypeMatchers.subTypeOf(javax.net.ssl.SSLSocket.class)
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
            .transform(
                (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                    typeBuilder.visit(
                        // Implicit handshakes (first read/write on an unconnected TLS
                        // socket) go through the private startHandshake(boolean) without
                        // calling startHandshake(); the SCOPE_SSL_HANDSHAKE scope evaluates an
                        // explicit startHandshake() → startHandshake(true) chain once.
                        advise(
                            SocketAdvice.SslHandshakeAdvice.class,
                            ElementMatchers.named("startHandshake")
                                .and(
                                    ElementMatchers.takesArguments(0)
                                        .or(ElementMatchers.takesArguments(boolean.class))))));

    agentBuilder =
        agentBuilder
            .type(
                TypeMatchers.subTypeOf(javax.net.ssl.SSLEngine.class)
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
            .transform(
                (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                    typeBuilder.visit(
                        advise(
                            SocketAdvice.SslHandshakeAdvice.class,
                            ElementMatchers.named("beginHandshake")
                                .and(ElementMatchers.takesArguments(0)))));

    return agentBuilder;
  }
}
