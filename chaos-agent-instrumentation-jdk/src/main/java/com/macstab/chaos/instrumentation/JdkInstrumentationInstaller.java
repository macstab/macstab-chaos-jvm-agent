package com.macstab.chaos.instrumentation;

import com.macstab.chaos.core.ChaosRuntime;
import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Static-only installer that wires ByteBuddy instrumentation for all JDK interception points.
 *
 * <h2>Startup sequence</h2>
 *
 * <ol>
 *   <li>{@link #install} is called once by {@code ChaosAgentBootstrap} after the runtime is ready.
 *   <li>{@link #injectBridge(java.lang.instrument.Instrumentation)} packages {@link
 *       com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher} and {@link
 *       com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher.ThrowingSupplier} into a
 *       temporary JAR and appends it to the bootstrap classpath so that bootstrap-loaded JDK
 *       classes can see it.
 *   <li>{@link #installDelegate(Object)} constructs a {@link
 *       com.macstab.chaos.instrumentation.ChaosBridge}, builds the 46-slot {@link
 *       java.lang.invoke.MethodHandle} array via {@link #buildMethodHandles}, and calls the
 *       bootstrap-loaded {@code BootstrapDispatcher.install()} via reflection to wire the bridge.
 *   <li>ByteBuddy's {@code AgentBuilder} is assembled with one transformation per interception
 *       point and installed via {@link
 *       net.bytebuddy.agent.builder.AgentBuilder#installOn(java.lang.instrument.Instrumentation)}.
 * </ol>
 *
 * <h2>Phase 1 vs Phase 2</h2>
 *
 * <p><b>Phase 1</b> interception points (thread pool, scheduling, queues, shutdown hooks, class
 * loading) are always installed.
 *
 * <p><b>Phase 2</b> interception points (clock, GC, exit, NIO, sockets, JNDI, serialization, native
 * libraries, JMX, ThreadLocal, etc.) are gated behind the {@code premainMode} flag. The flag is set
 * to {@code true} both when the agent is attached at JVM startup via {@code -javaagent:} and when
 * it is self-attached at runtime via {@code ChaosAgentBootstrap#installForLocalTests()}. In both
 * modes the installer relies on ByteBuddy's {@code RedefinitionStrategy.RETRANSFORMATION} combined
 * with {@code disableClassFormatChanges()} to rewrite already-loaded JDK classes such as {@code
 * java.net.Socket}, so the same interception points fire regardless of whether the agent was on the
 * command line or attached programmatically.
 *
 * <h2>Retransformation</h2>
 *
 * <p>All transformations use {@code disableClassFormatChanges()} combined with {@code
 * RedefinitionStrategy.RETRANSFORMATION} to allow live retransformation of already-loaded JDK
 * classes without changing the class format.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless. {@link #install} is expected to be called exactly once at startup,
 * from a single thread.
 */
public final class JdkInstrumentationInstaller {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  private static final String BRIDGE_JAR_PREFIX = "macstab-chaos-bootstrap-bridge";
  private static final String BRIDGE_JAR_SUFFIX = ".jar";

  /**
   * The transformer returned by the last successful {@link AgentBuilder#installOn} call. Retained
   * so dynamic-attach test runners and framework teardown paths can call {@link #uninstall} to
   * reset applied class transformations. {@code volatile} so the latest reference is visible to
   * {@link #uninstall} on any thread.
   */
  private static volatile ResettableClassFileTransformer installedTransformer;

  private JdkInstrumentationInstaller() {}

  /**
   * Entry point called once at agent startup to install all ByteBuddy transformations.
   *
   * <p>The method first injects the bootstrap bridge JAR, then wires the {@link ChaosBridge}
   * delegate, and finally assembles and installs the {@code AgentBuilder}. Phase 1 transformations
   * are always applied. Phase 2 transformations (JVM runtime interception points such as clock, GC,
   * NIO, and sockets) are applied only when {@code premainMode} is {@code true} because those
   * points require retransformation of already-loaded JDK classes.
   *
   * @param instrumentation the {@link Instrumentation} handle provided by the JVM at agent
   *     attachment; used both for bootstrap classpath injection and for installing transformations
   * @param runtime the live {@link ChaosRuntime} instance that supplies the active scenario
   *     configuration to the bridge
   * @param premainMode {@code true} to enable Phase 2 JVM-level interception. Set by both {@code
   *     premain}/{@code agentmain} and by self-attach test helpers so that Socket/NIO/HTTP chaos
   *     fires in every supported attach path. Pass {@code false} only from callers that cannot rely
   *     on retransformation (legacy attach flows that pre-date JVMTI class retransformation
   *     support).
   */
  public static void install(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean premainMode) {
    injectBridge(instrumentation);
    installDelegate(new ChaosBridge(runtime));

    final AgentBuilder.Listener.Adapter errorListener =
        new AgentBuilder.Listener.Adapter() {
          @Override
          public void onError(
              final String typeName,
              final ClassLoader classLoader,
              final JavaModule module,
              final boolean loaded,
              final Throwable throwable) {
            LOGGER.warning("chaos instrumentation failed for " + typeName + ": " + throwable);
          }
        };

    AgentBuilder agentBuilder =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(errorListener)
            .ignore(
                ElementMatchers.nameStartsWith("net.bytebuddy.")
                    .or(ElementMatchers.nameStartsWith("com.macstab.chaos.")));

    agentBuilder =
        agentBuilder
            .type(ElementMatchers.named("java.lang.Thread"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder.visit(
                        Advice.to(ThreadAdvice.StartAdvice.class)
                            .on(
                                ElementMatchers.named("start")
                                    .and(ElementMatchers.takesArguments(0)))))
            .type(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder
                        .visit(
                            Advice.to(ExecutorAdvice.ExecuteAdvice.class)
                                .on(
                                    ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArguments(Runnable.class))))
                        .visit(
                            Advice.to(ExecutorAdvice.BeforeExecuteAdvice.class)
                                .on(
                                    ElementMatchers.named("beforeExecute")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Thread.class, Runnable.class))))
                        .visit(
                            Advice.to(ExecutorAdvice.ShutdownAdvice.class)
                                .on(
                                    ElementMatchers.named("shutdown")
                                        .and(ElementMatchers.takesArguments(0))))
                        // shutdownNow() intercepts the forced-shutdown variant that interrupts
                        // in-flight tasks and returns the undrained queue. Omitting it left any
                        // selector targeting EXECUTOR_SHUTDOWN silently missing shutdown paths
                        // that went through shutdownNow instead of shutdown.
                        .visit(
                            Advice.to(ExecutorAdvice.ShutdownNowAdvice.class)
                                .on(
                                    ElementMatchers.named("shutdownNow")
                                        .and(ElementMatchers.takesArguments(0))))
                        .visit(
                            Advice.to(ExecutorAdvice.AwaitTerminationAdvice.class)
                                .on(
                                    ElementMatchers.named("awaitTermination")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                long.class, java.util.concurrent.TimeUnit.class)))))
            .type(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.ScheduleRunnableAdvice.class)
                                .on(
                                    ElementMatchers.named("schedule")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.ScheduleCallableAdvice.class)
                                .on(
                                    ElementMatchers.named("schedule")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Callable.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.PeriodicAdvice.class)
                                .on(
                                    ElementMatchers.named("scheduleAtFixedRate")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.PeriodicAdvice.class)
                                .on(
                                    ElementMatchers.named("scheduleWithFixedDelay")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class)))));

    if (premainMode) {
      agentBuilder =
          agentBuilder
              .type(
                  ElementMatchers.isSubTypeOf(java.util.concurrent.BlockingQueue.class)
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(QueueAdvice.PutAdvice.class)
                                  .on(ElementMatchers.named("put")))
                          .visit(
                              Advice.to(QueueAdvice.TakeAdvice.class)
                                  .on(ElementMatchers.named("take")))
                          .visit(
                              Advice.to(QueueAdvice.PollAdvice.class)
                                  .on(ElementMatchers.named("poll")))
                          .visit(
                              Advice.to(QueueAdvice.OfferAdvice.class)
                                  .on(
                                      ElementMatchers.named("offer")
                                          .and(ElementMatchers.takesArguments(1)))))
              .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(CompletableFutureAdvice.CompleteAdvice.class)
                                  .on(
                                      ElementMatchers.named("complete")
                                          .and(ElementMatchers.takesArguments(1))))
                          .visit(
                              Advice.to(CompletableFutureAdvice.CompleteExceptionallyAdvice.class)
                                  .on(
                                      ElementMatchers.named("completeExceptionally")
                                          .and(ElementMatchers.takesArguments(Throwable.class)))))
              .type(ElementMatchers.named("java.lang.ClassLoader"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              // Must match BOTH 1-arg and 2-arg overloads: BuiltinClassLoader
                              // (the superclass of every default JDK classloader) overrides the
                              // 2-arg variant but inherits the 1-arg from java.lang.ClassLoader,
                              // so instrumenting only the 2-arg would miss every standard class
                              // load via Class.forName / loader.loadClass(name). Custom
                              // classloaders that inherit both will double-dispatch, which is a
                              // known cost of the single-class retransformation strategy.
                              Advice.to(ClassLoaderAdvice.LoadClassAdvice.class)
                                  .on(
                                      ElementMatchers.named("loadClass")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                      String.class, boolean.class)
                                                  .or(
                                                      ElementMatchers.takesArguments(
                                                          String.class)))))
                          .visit(
                              Advice.to(ClassLoaderAdvice.GetResourceAdvice.class)
                                  .on(
                                      ElementMatchers.named("getResource")
                                          .and(ElementMatchers.takesArguments(String.class)))))
              .type(ElementMatchers.named("java.lang.Runtime"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(ShutdownAdvice.AddShutdownHookAdvice.class)
                                  .on(
                                      ElementMatchers.named("addShutdownHook")
                                          .and(ElementMatchers.takesArguments(Thread.class))))
                          .visit(
                              Advice.to(ShutdownAdvice.RemoveShutdownHookAdvice.class)
                                  .on(
                                      ElementMatchers.named("removeShutdownHook")
                                          .and(ElementMatchers.takesArguments(Thread.class)))))
              .type(ElementMatchers.named("java.util.concurrent.ForkJoinTask"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(ForkJoinAdvice.DoExecAdvice.class)
                              .on(
                                  ElementMatchers.named("doExec")
                                      .and(ElementMatchers.takesArguments(0)))));

      // ── Phase 2: JVM runtime interception ─────────────────────────────────
      agentBuilder =
          agentBuilder
              // System.exit() — Java wrapper, safe to instrument with disableClassFormatChanges().
              // currentTimeMillis() and nanoTime() are @IntrinsicCandidate native methods and
              // are handled separately below via the native-method-prefix AgentBuilder.
              .type(ElementMatchers.named("java.lang.System"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ExitRequestAdvice.class)
                              .on(
                                  ElementMatchers.named("exit")
                                      .and(ElementMatchers.takesArguments(int.class)))))
              // GC and halt via Runtime
              .type(ElementMatchers.named("java.lang.Runtime"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.GcRequestAdvice.class)
                                  .on(
                                      ElementMatchers.named("gc")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ExitRequestAdvice.class)
                                  .on(
                                      ElementMatchers.named("halt")
                                          .and(ElementMatchers.takesArguments(int.class)))))
              // Reflection
              .type(ElementMatchers.named("java.lang.reflect.Method"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ReflectionInvokeAdvice.class)
                              .on(
                                  ElementMatchers.named("invoke")
                                      .and(
                                          ElementMatchers.takesArguments(
                                              Object.class, Object[].class)))))
              // Direct buffer allocation
              .type(ElementMatchers.named("java.nio.ByteBuffer"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.DirectBufferAllocateAdvice.class)
                              .on(
                                  ElementMatchers.named("allocateDirect")
                                      .and(ElementMatchers.takesArguments(int.class)))))
              // Object deserialization and serialization
              .type(ElementMatchers.named("java.io.ObjectInputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ObjectDeserializeAdvice.class)
                              .on(
                                  ElementMatchers.named("readObject")
                                      .and(ElementMatchers.takesArguments(0)))))
              .type(ElementMatchers.named("java.io.ObjectOutputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ObjectSerializeAdvice.class)
                              .on(
                                  ElementMatchers.named("writeObject")
                                      .and(ElementMatchers.takesArguments(Object.class)))))
              // ClassLoader.defineClass
              .type(ElementMatchers.named("java.lang.ClassLoader"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ClassDefineAdvice.class)
                              .on(
                                  ElementMatchers.named("defineClass")
                                      .and(ElementMatchers.takesArgument(0, String.class)))))
              // LockSupport.park / parkNanos / parkUntil (THREAD_PARK)
              // Safe to instrument globally: the chaos runtime never calls LockSupport.park()
              // directly. Internal locks (ManualGate via ReentrantLock) do call park(), but by
              // the time park() fires, BootstrapDispatcher.DEPTH is already > 0 (set before
              // delegating to ChaosRuntime), so the DEPTH guard in invoke() returns the fallback
              // immediately without entering chaos evaluation.
              .type(ElementMatchers.named("java.util.concurrent.locks.LockSupport"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          // park(Object blocker) — used by j.u.c. locks that set the blocker
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("park")
                                          .and(ElementMatchers.takesArguments(Object.class))))
                          // park() — 0-arg variant used by direct LockSupport callers
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("park")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkNanos")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  Object.class, long.class))))
                          // parkNanos(long nanos) — 1-arg variant without blocker
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkNanos")
                                          .and(ElementMatchers.takesArguments(long.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkUntil")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  Object.class, long.class))))
                          // parkUntil(long deadline) — 1-arg variant without blocker
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkUntil")
                                          .and(ElementMatchers.takesArguments(long.class)))))
              // AQS.acquire (MONITOR_ENTER proxy)
              // Safe: ChaosRuntime uses ConcurrentHashMap (lock-free) for registry.match().
              // The one internal AQS user is ManualGate (ReentrantLock), but ManualGate is only
              // entered from applyGate() which runs inside DEPTH > 0 context — the DEPTH guard
              // short-circuits before chaos evaluation begins, preventing recursion.
              .type(ElementMatchers.named("java.util.concurrent.locks.AbstractQueuedSynchronizer"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.MonitorEnterAdvice.class)
                              .on(
                                  ElementMatchers.named("acquire")
                                      .and(ElementMatchers.takesArguments(int.class)))))
              // NIO Selector — target AbstractSelector subtypes (KQueueSelectorImpl etc.).
              // Exclude abstract / interface / synthetic matches for the same VerifyError reason
              // as SocketChannel below: bytecode generated for bridge / synthetic methods on
              // abstract bases cannot satisfy the advice's stack-map expectations.
              .type(
                  ElementMatchers.isSubTypeOf(java.nio.channels.spi.AbstractSelector.class)
                      .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioSelectNoArgAdvice.class)
                                  .on(
                                      ElementMatchers.named("select")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioSelectTimeoutAdvice.class)
                                  .on(
                                      ElementMatchers.named("select")
                                          .and(ElementMatchers.takesArguments(long.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioSelectNowAdvice.class)
                                  .on(
                                      ElementMatchers.named("selectNow")
                                          .and(ElementMatchers.takesArguments(0)))))
              // NIO SocketChannel — the public class is abstract, so name() would only match the
              // abstract copy; instrument concrete subtypes (sun.nio.ch.SocketChannelImpl,
              // possibly vendor variants) where the actual I/O code lives. Exclude abstract /
              // synthetic matches to avoid `VerifyError` on bridge methods.
              .type(
                  ElementMatchers.isSubTypeOf(java.nio.channels.SocketChannel.class)
                      .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioChannelConnectAdvice.class)
                                  .on(
                                      ElementMatchers.named("connect")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  java.net.SocketAddress.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioChannelReadAdvice.class)
                                  .on(
                                      ElementMatchers.named("read")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  java.nio.ByteBuffer.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.NioChannelWriteAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  java.nio.ByteBuffer.class)))))
              // NIO ServerSocketChannel — same abstract-base issue as SocketChannel above.
              .type(
                  ElementMatchers.isSubTypeOf(java.nio.channels.ServerSocketChannel.class)
                      .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.NioChannelAcceptAdvice.class)
                              .on(
                                  ElementMatchers.named("accept")
                                      .and(ElementMatchers.takesArguments(0)))))
              // Socket
              .type(ElementMatchers.named("java.net.Socket"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.SocketConnectAdvice.class)
                                  .on(
                                      ElementMatchers.named("connect")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  java.net.SocketAddress.class, int.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.SocketCloseAdvice.class)
                                  .on(
                                      ElementMatchers.named("close")
                                          .and(ElementMatchers.takesArguments(0)))))
              // ServerSocket
              .type(ElementMatchers.named("java.net.ServerSocket"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SocketAcceptAdvice.class)
                              .on(
                                  ElementMatchers.named("accept")
                                      .and(ElementMatchers.takesArguments(0)))))
              // SocketInputStream / SocketOutputStream (package-private)
              // Only instrument the 3-argument bulk variant of read(): the 0-arg single-byte
              // overload delegates internally to read(byte[], int, int), causing double-fire of
              // beforeSocketRead for each single-byte application call. Restricting to the final
              // I/O dispatch avoids double-counting rate-limit permits and probability draws.
              .type(ElementMatchers.named("java.net.SocketInputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SocketReadAdvice.class)
                              .on(
                                  ElementMatchers.named("read")
                                      .and(
                                          ElementMatchers.takesArguments(
                                              byte[].class, int.class, int.class)))))
              .type(ElementMatchers.named("java.net.SocketOutputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.SocketWriteSingleByteAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(ElementMatchers.takesArguments(int.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.SocketWriteBulkAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  byte[].class, int.class, int.class)))))
              // ZIP Inflater / Deflater
              .type(ElementMatchers.named("java.util.zip.Inflater"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ZipInflateAdvice.class)
                              .on(ElementMatchers.named("inflate"))))
              .type(ElementMatchers.named("java.util.zip.Deflater"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ZipDeflateAdvice.class)
                              .on(ElementMatchers.named("deflate"))));

      // ThreadLocal.get() / set() (THREAD_LOCAL_GET / THREAD_LOCAL_SET)
      // Previously excluded due to reentrancy with BootstrapDispatcher.DEPTH (also a ThreadLocal).
      // Now safe: ThreadLocalGetAdvice and ThreadLocalSetAdvice include an identity check
      // (threadLocal == BootstrapDispatcher.DEPTH) that exits before any delegation, breaking
      // the recursion at the only point where it could occur. See ThreadLocalGetAdvice for the
      // full reentrancy analysis.
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.lang.ThreadLocal"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadLocalGetAdvice.class)
                                  .on(
                                      ElementMatchers.named("get")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadLocalSetAdvice.class)
                                  .on(
                                      ElementMatchers.named("set")
                                          .and(ElementMatchers.takesArguments(1)))));

      // Optional instrumentation — skip gracefully if class not present
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "javax.naming.InitialContext",
              builder ->
                  builder.visit(
                      Advice.to(JvmRuntimeAdvice.JndiLookupAdvice.class)
                          .on(
                              ElementMatchers.named("lookup")
                                  .and(ElementMatchers.takesArguments(String.class)))));

      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "javax.management.MBeanServer",
              builder ->
                  builder
                      .visit(
                          Advice.to(JvmRuntimeAdvice.JmxInvokeAdvice.class)
                              .on(
                                  ElementMatchers.named("invoke")
                                      .and(ElementMatchers.takesArguments(4))))
                      .visit(
                          Advice.to(JvmRuntimeAdvice.JmxGetAttrAdvice.class)
                              .on(
                                  ElementMatchers.named("getAttribute")
                                      .and(ElementMatchers.takesArguments(2)))));

      // On JDK 8-16 native library loading went through Runtime.loadLibrary0; on JDK 17+ the
      // method was moved to jdk.internal.loader.NativeLibraries.load (private JDK internals). We
      // weave both sites so NATIVE_LIBRARY_LOAD fires on every supported JDK; missing sites are
      // tolerated by instrumentOptional so a mismatch is never a startup failure.
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "java.lang.Runtime",
              builder ->
                  builder.visit(
                      Advice.to(JvmRuntimeAdvice.NativeLibraryLoadAdvice.class)
                          .on(ElementMatchers.named("loadLibrary0"))));
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "jdk.internal.loader.NativeLibraries",
              builder ->
                  builder.visit(
                      Advice.to(JvmRuntimeAdvice.NativeLibrariesLoadAdvice.class)
                          .on(ElementMatchers.named("load"))));

      // CompletableFuture.cancel
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.AsyncCancelAdvice.class)
                              .on(
                                  ElementMatchers.named("cancel")
                                      .and(ElementMatchers.takesArguments(boolean.class)))));

      // ── Higher-level time APIs ────────────────────────────────────────────
      // Direct System.currentTimeMillis() / System.nanoTime() cannot be intercepted (see note
      // below) but java.time.Instant.now(), java.time.LocalDateTime.now(), java.time.ZonedDateTime
      // .now(), and java.util.Date() are regular Java members that can be woven at exit.
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.time.Instant"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.InstantNowAdvice.class)
                              .on(
                                  ElementMatchers.named("now")
                                      .and(ElementMatchers.isStatic())
                                      .and(ElementMatchers.takesArguments(0)))))
              .type(ElementMatchers.named("java.time.LocalDateTime"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.LocalDateTimeNowAdvice.class)
                              .on(
                                  ElementMatchers.named("now")
                                      .and(ElementMatchers.isStatic())
                                      .and(ElementMatchers.takesArguments(0)))))
              .type(ElementMatchers.named("java.time.ZonedDateTime"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.ZonedDateTimeNowAdvice.class)
                              .on(
                                  ElementMatchers.named("now")
                                      .and(ElementMatchers.isStatic())
                                      .and(ElementMatchers.takesArguments(0)))))
              .type(ElementMatchers.named("java.util.Date"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.DateNewAdvice.class)
                              .on(
                                  ElementMatchers.isConstructor()
                                      .and(ElementMatchers.takesArguments(0)))));

      // ── HTTP client interception (2.3) ────────────────────────────────────
      // All targets use instrumentOptional, which checks class presence before registering. If
      // the target is absent, no transformation is added. Advice classes only use Object-typed
      // arguments and reflective URL extraction, so the compileOnly dependencies (OkHttp,
      // Apache HC, Reactor Netty) are not required at runtime.
      //
      // Java 11+ HttpClient (jdk.internal.net.http.HttpClientImpl) is NOT registered via
      // instrumentOptional here. That class is always present on JDK 11+, but it lives in the
      // non-exported java.net.http/jdk.internal.net.http package. Attempting to transform it
      // without --add-opens java.net.http/jdk.internal.net.http=ALL-UNNAMED silently corrupts
      // subsequent AgentBuilder transformations. If interception of the Java HttpClient is
      // required, users can target its public API or wait for a dedicated --add-opens pathway.
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "okhttp3.RealCall",
              builder ->
                  builder
                      .visit(
                          Advice.to(HttpClientAdvice.OkHttpExecuteAdvice.class)
                              .on(
                                  ElementMatchers.named("execute")
                                      .and(ElementMatchers.takesArguments(0))))
                      .visit(
                          Advice.to(HttpClientAdvice.OkHttpEnqueueAdvice.class)
                              .on(
                                  ElementMatchers.named("enqueue")
                                      .and(ElementMatchers.takesArguments(1)))));

      // Apache HC 4.x exposes eight execute() overloads split across two shapes:
      //   execute(HttpHost, HttpRequest [, HttpContext] [, ResponseHandler])  — HttpHost first
      //   execute(HttpUriRequest [, HttpContext] [, ResponseHandler])         — request first
      //
      // A flat takesArguments(2) matcher (the previous wiring) bound
      // ApacheHc4ExecuteAdvice indiscriminately to all three 2-arg overloads,
      // handing the advice an HttpUriRequest where it expected an HttpHost and
      // leaving single-arg + 3-arg + 4-arg variants completely uncovered. The
      // refined matchers below disambiguate by first-argument type so every
      // overload routes to the advice that understands its signature.
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "org.apache.http.impl.client.CloseableHttpClient",
              builder ->
                  builder
                      .visit(
                          Advice.to(HttpClientAdvice.ApacheHc4ExecuteAdvice.class)
                              .on(
                                  ElementMatchers.named("execute")
                                      .and(
                                          ElementMatchers.takesArgument(
                                              0,
                                              ElementMatchers.named("org.apache.http.HttpHost")))))
                      .visit(
                          Advice.to(HttpClientAdvice.ApacheHc4UriExecuteAdvice.class)
                              .on(
                                  ElementMatchers.named("execute")
                                      .and(
                                          ElementMatchers.takesArgument(
                                              0,
                                              ElementMatchers.named(
                                                  "org.apache.http.client.methods.HttpUriRequest"))))));

      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
              builder ->
                  builder.visit(
                      Advice.to(HttpClientAdvice.ApacheHc5ExecuteAdvice.class)
                          .on(
                              ElementMatchers.named("execute")
                                  // 2-arg: execute(ClassicHttpRequest, ClassicHttpResponseHandler)
                                  // 3-arg: execute(ClassicHttpRequest, HttpContext,
                                  //                ClassicHttpResponseHandler)
                                  .and(
                                      ElementMatchers.takesArguments(2)
                                          .or(ElementMatchers.takesArguments(3))))));

      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "reactor.netty.http.client.HttpClientConnect",
              builder ->
                  builder.visit(
                      Advice.to(HttpClientAdvice.ReactorNettyConnectAdvice.class)
                          .on(ElementMatchers.named("connect"))));

      // ── JDBC / connection pool interception (2.4) ──────────────────────────
      //
      // HikariCP and c3p0 are compileOnly dependencies and are instrumented via
      // instrumentOptional so their absence is tolerated. java.sql.Statement and
      // java.sql.Connection are JDK interfaces — we restrict the type matcher to
      // concrete subtypes via isSubTypeOf + not(isInterface()) so the advice only
      // binds to implementations (driver classes such as HikariProxyStatement,
      // org.postgresql.jdbc.PgStatement, etc.).
      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "com.zaxxer.hikari.pool.HikariPool",
              builder ->
                  builder.visit(
                      Advice.to(JdbcAdvice.HikariGetConnectionAdvice.class)
                          .on(
                              ElementMatchers.named("getConnection")
                                  .and(ElementMatchers.takesArguments(long.class)))));

      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool",
              builder ->
                  builder.visit(
                      Advice.to(JdbcAdvice.C3p0CheckoutAdvice.class)
                          .on(
                              ElementMatchers.named("checkoutPooledConnection")
                                  .and(ElementMatchers.takesArguments(0)))));

      // Match every Statement.execute*/Connection.prepareStatement overload whose first
      // argument is the SQL String. The JDBC API defines generated-keys variants
      // (execute(String, int), execute(String, int[]), execute(String, String[]),
      // executeUpdate(String, int), ..., prepareStatement(String, int),
      // prepareStatement(String, int[]), prepareStatement(String, int, int),
      // prepareStatement(String, int, int, int), prepareStatement(String, String[]))
      // that restricting to takesArguments(String.class) silently skipped — any JDBC
      // caller using Statement.RETURN_GENERATED_KEYS or column-name arrays bypassed
      // every chaos selector targeting JDBC. The advice only reads @Argument(0), so
      // takesArgument(0, String.class) is sufficient and binds to all overloads.
      agentBuilder =
          agentBuilder
              .type(
                  ElementMatchers.isSubTypeOf(java.sql.Statement.class)
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JdbcAdvice.StatementExecuteAdvice.class)
                                  .on(
                                      ElementMatchers.named("execute")
                                          .and(ElementMatchers.takesArgument(0, String.class))))
                          .visit(
                              Advice.to(JdbcAdvice.StatementExecuteAdvice.class)
                                  .on(
                                      ElementMatchers.named("executeQuery")
                                          .and(ElementMatchers.takesArgument(0, String.class))))
                          .visit(
                              Advice.to(JdbcAdvice.StatementExecuteAdvice.class)
                                  .on(
                                      ElementMatchers.named("executeUpdate")
                                          .and(ElementMatchers.takesArgument(0, String.class))))
                          .visit(
                              Advice.to(JdbcAdvice.StatementExecuteAdvice.class)
                                  .on(
                                      ElementMatchers.named("executeLargeUpdate")
                                          .and(ElementMatchers.takesArgument(0, String.class)))))
              .type(
                  ElementMatchers.isSubTypeOf(java.sql.Connection.class)
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JdbcAdvice.PrepareStatementAdvice.class)
                                  .on(
                                      ElementMatchers.named("prepareStatement")
                                          .and(ElementMatchers.takesArgument(0, String.class))))
                          .visit(
                              Advice.to(JdbcAdvice.PrepareStatementAdvice.class)
                                  .on(
                                      ElementMatchers.named("prepareCall")
                                          .and(ElementMatchers.takesArgument(0, String.class))))
                          .visit(
                              Advice.to(JdbcAdvice.CommitAdvice.class)
                                  .on(
                                      ElementMatchers.named("commit")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JdbcAdvice.RollbackAdvice.class)
                                  .on(
                                      ElementMatchers.named("rollback")
                                          .and(ElementMatchers.takesArguments(0)))));
      // ── Thread.sleep interception (2.5) ───────────────────────────────────
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.lang.Thread"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          // sleep(long millis)
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadSleepAdvice.class)
                                  .on(
                                      ElementMatchers.named("sleep")
                                          .and(ElementMatchers.isStatic())
                                          .and(ElementMatchers.takesArguments(long.class))))
                          // sleep(long millis, int nanos) — advice reads only arg 0 (millis)
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadSleepAdvice.class)
                                  .on(
                                      ElementMatchers.named("sleep")
                                          .and(ElementMatchers.isStatic())
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  long.class, int.class)))));

      // ── DNS resolution interception (2.6) ──────────────────────────────────
      // InetAddress is always present; no instrumentOptional needed.
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.net.InetAddress"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.DnsResolveAdvice.class)
                                  .on(
                                      ElementMatchers.named("getByName")
                                          .and(ElementMatchers.isStatic())
                                          .and(ElementMatchers.takesArguments(String.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.DnsResolveAdvice.class)
                                  .on(
                                      ElementMatchers.named("getAllByName")
                                          .and(ElementMatchers.isStatic())
                                          .and(ElementMatchers.takesArguments(String.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.DnsLocalHostAdvice.class)
                                  .on(
                                      ElementMatchers.named("getLocalHost")
                                          .and(ElementMatchers.isStatic())
                                          .and(ElementMatchers.takesArguments(0)))));

      // ── SSL/TLS handshake interception (2.7) ──────────────────────────────
      // SSLSocket and SSLEngine are abstract; concrete subclasses (SSLSocketImpl,
      // SSLEngineImpl) override startHandshake/beginHandshake, so advice on the
      // abstract class alone would never fire. Target all concrete subtypes instead.
      // Interface / synthetic filters exclude Mockito/CGLIB generated proxies that would
      // otherwise trip VerifyError during class transformation.
      agentBuilder =
          agentBuilder
              .type(
                  ElementMatchers.isSubTypeOf(javax.net.ssl.SSLSocket.class)
                      .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SslHandshakeAdvice.class)
                              .on(
                                  ElementMatchers.named("startHandshake")
                                      .and(ElementMatchers.takesArguments(0)))));

      agentBuilder =
          agentBuilder
              .type(
                  ElementMatchers.isSubTypeOf(javax.net.ssl.SSLEngine.class)
                      .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                      .and(ElementMatchers.not(ElementMatchers.isInterface()))
                      .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SslHandshakeAdvice.class)
                              .on(
                                  ElementMatchers.named("beginHandshake")
                                      .and(ElementMatchers.takesArguments(0)))));

      // ── File I/O interception (2.8) ────────────────────────────────────────
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.named("java.io.FileInputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileReadAdvice.class)
                                  .on(
                                      ElementMatchers.named("read")
                                          .and(ElementMatchers.takesArguments(0))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileReadAdvice.class)
                                  .on(
                                      ElementMatchers.named("read")
                                          .and(ElementMatchers.takesArguments(byte[].class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileReadAdvice.class)
                                  .on(
                                      ElementMatchers.named("read")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  byte[].class, int.class, int.class)))))
              .type(ElementMatchers.named("java.io.FileOutputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileWriteAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(ElementMatchers.takesArguments(int.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileWriteAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(ElementMatchers.takesArguments(byte[].class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.FileWriteAdvice.class)
                                  .on(
                                      ElementMatchers.named("write")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  byte[].class, int.class, int.class)))));
    }

    // Retain the transformer returned by installOn so uninstall() can reset class transformations.
    // Without this, once ByteBuddy has rewritten bootstrap-loaded JDK classes there is no handle
    // to undo the transformation — dynamic-attach test runners that want to detach the agent
    // between suites have no way back. Last-writer-wins is acceptable because ChaosAgentBootstrap's
    // RUNTIME CAS serialises install() calls JVM-wide to at most one.
    installedTransformer = agentBuilder.installOn(instrumentation);

    // ── Clock skew limitation: direct System.currentTimeMillis() cannot be intercepted ────────
    //
    // System.currentTimeMillis() and System.nanoTime() are @IntrinsicCandidate native methods
    // in java.lang.System (java.base module). Two independent JVM constraints prevent advice
    // from reaching them:
    //
    // Constraint 1 — Retransformation cannot add methods or change native modifiers.
    //   JVMTI SetNativeMethodPrefix works by transforming a native method to a Java wrapper that
    //   calls the renamed native ($chaos$currentTimeMillis). This requires (a) adding the renamed
    //   native method and (b) changing currentTimeMillis() from native to non-native. Both
    //   operations are prohibited by the JVM spec for retransformation of already-loaded classes.
    //   java.lang.System is loaded before premain runs, so this approach is unavailable.
    //
    // Constraint 2 — @IntrinsicCandidate JIT bypass (secondary; moot given constraint 1).
    //   Even if a Java wrapper existed, HotSpot C2 JIT recognises
    // java.lang.System.currentTimeMillis
    //   by class+method name and replaces the call with a direct RDTSC / MRS CNTVCT_EL0 hardware
    //   read, bypassing the wrapper entirely after ~10 000 invocations.
    //
    // Investigation: a second AgentBuilder with enableNativeMethodPrefix("$chaos$") and
    // RETRANSFORMATION was tested. ByteBuddy fires a TRANSFORM event for java.lang.System but
    // the JVM silently retains the original native binding. currentTimeMillis() remains native
    // with its original hardware-clock implementation. No error is reported.
    //
    // Consequence: ClockSkewEffect cannot intercept direct System.currentTimeMillis() or
    // System.nanoTime() calls. Clock skew IS applied through two supported paths:
    //   1. Code explicitly calls BootstrapDispatcher.adjustClockMillis / adjustClockNanos
    //      (the hot path wired by this agent for custom instrumentation points).
    //   2. Application code uses java.time.Instant.now(), java.time.LocalDateTime.now(),
    //      java.time.ZonedDateTime.now(), or new java.util.Date() — these are non-native Java
    //      members that are woven above in the Phase 2 block when the agent is attached via
    //      -javaagent: at JVM startup.
    //
    // This is a hard JVM limitation for any standard -javaagent: Java instrumentation agent.
    // Workarounds (e.g. -Xpatch:java.base, a C-level JVMTI agent) are out of scope.
  }

  /**
   * Resets the installed ByteBuddy transformer, reverting class transformations applied by the most
   * recent {@link #install} call.
   *
   * <p>Callable by dynamic-attach test runners between suites, or by framework teardown paths that
   * need to detach chaos interception without restarting the JVM. Safe to call when no install has
   * occurred — the no-transformer state is a silent no-op.
   *
   * @param instrumentation the same {@link Instrumentation} handle originally passed to {@link
   *     #install}; required so the underlying JVMTI retransformation can see the previously
   *     transformed classes
   * @return {@code true} if a transformer was reset; {@code false} if no transformer was recorded
   *     (i.e. {@link #install} was never called or already uninstalled)
   */
  public static boolean uninstall(final Instrumentation instrumentation) {
    final ResettableClassFileTransformer current = installedTransformer;
    if (current == null) {
      return false;
    }
    installedTransformer = null;
    current.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    return true;
  }

  /**
   * Builds the fixed-size {@link MethodHandle} array that backs {@link
   * com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher}.
   *
   * <p>Each slot in the returned array corresponds to one of the {@code public static final int}
   * index constants declared on {@link
   * com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher} (e.g. {@code
   * ADJUST_CLOCK_MILLIS}, {@code BEFORE_GC_REQUEST}, etc.). The array has exactly {@link
   * com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher#HANDLE_COUNT} elements (currently
   * 46), one per dispatch slot. All handles are resolved against the {@link BridgeDelegate}
   * interface using a public lookup so that they are callable from the bootstrap classloader
   * context.
   *
   * @return a 46-element array of {@link MethodHandle} objects indexed by the {@code HANDLE_*}
   *     constants on {@link com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher}; no
   *     element is {@code null}
   * @throws Exception if any required method is absent from {@link BridgeDelegate} or if the lookup
   *     fails for any reason
   */
  static MethodHandle[] buildMethodHandles() throws Exception {
    final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    final Class<?> cls = BridgeDelegate.class;
    final MethodHandle[] mh = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    mh[BootstrapDispatcher.DECORATE_EXECUTOR_RUNNABLE] =
        lookup.findVirtual(
            cls,
            "decorateExecutorRunnable",
            MethodType.methodType(Runnable.class, String.class, Object.class, Runnable.class));
    mh[BootstrapDispatcher.DECORATE_EXECUTOR_CALLABLE] =
        lookup.findVirtual(
            cls,
            "decorateExecutorCallable",
            MethodType.methodType(Callable.class, String.class, Object.class, Callable.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_START] =
        lookup.findVirtual(
            cls, "beforeThreadStart", MethodType.methodType(void.class, Thread.class));
    mh[BootstrapDispatcher.BEFORE_WORKER_RUN] =
        lookup.findVirtual(
            cls,
            "beforeWorkerRun",
            MethodType.methodType(void.class, Object.class, Thread.class, Runnable.class));
    mh[BootstrapDispatcher.BEFORE_FORK_JOIN_TASK_RUN] =
        lookup.findVirtual(
            cls, "beforeForkJoinTaskRun", MethodType.methodType(void.class, ForkJoinTask.class));
    mh[BootstrapDispatcher.ADJUST_SCHEDULE_DELAY] =
        lookup.findVirtual(
            cls,
            "adjustScheduleDelay",
            MethodType.methodType(
                long.class, String.class, Object.class, Object.class, long.class, boolean.class));
    mh[BootstrapDispatcher.BEFORE_SCHEDULED_TICK] =
        lookup.findVirtual(
            cls,
            "beforeScheduledTick",
            MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class));
    mh[BootstrapDispatcher.BEFORE_QUEUE_OPERATION] =
        lookup.findVirtual(
            cls,
            "beforeQueueOperation",
            MethodType.methodType(void.class, String.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_BOOLEAN_QUEUE_OPERATION] =
        lookup.findVirtual(
            cls,
            "beforeBooleanQueueOperation",
            MethodType.methodType(Boolean.class, String.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_COMPLETABLE_FUTURE_COMPLETE] =
        lookup.findVirtual(
            cls,
            "beforeCompletableFutureComplete",
            MethodType.methodType(
                Boolean.class, String.class, CompletableFuture.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_CLASS_LOAD] =
        lookup.findVirtual(
            cls,
            "beforeClassLoad",
            MethodType.methodType(void.class, ClassLoader.class, String.class));
    mh[BootstrapDispatcher.AFTER_RESOURCE_LOOKUP] =
        lookup.findVirtual(
            cls,
            "afterResourceLookup",
            MethodType.methodType(URL.class, ClassLoader.class, String.class, URL.class));
    mh[BootstrapDispatcher.DECORATE_SHUTDOWN_HOOK] =
        lookup.findVirtual(
            cls, "decorateShutdownHook", MethodType.methodType(Thread.class, Thread.class));
    mh[BootstrapDispatcher.RESOLVE_SHUTDOWN_HOOK] =
        lookup.findVirtual(
            cls, "resolveShutdownHook", MethodType.methodType(Thread.class, Thread.class));
    mh[BootstrapDispatcher.BEFORE_EXECUTOR_SHUTDOWN] =
        lookup.findVirtual(
            cls,
            "beforeExecutorShutdown",
            MethodType.methodType(void.class, String.class, Object.class, long.class));
    mh[BootstrapDispatcher.ADJUST_CLOCK_MILLIS] =
        lookup.findVirtual(cls, "adjustClockMillis", MethodType.methodType(long.class, long.class));
    mh[BootstrapDispatcher.ADJUST_CLOCK_NANOS] =
        lookup.findVirtual(cls, "adjustClockNanos", MethodType.methodType(long.class, long.class));
    mh[BootstrapDispatcher.BEFORE_GC_REQUEST] =
        lookup.findVirtual(cls, "beforeGcRequest", MethodType.methodType(boolean.class));
    mh[BootstrapDispatcher.BEFORE_EXIT_REQUEST] =
        lookup.findVirtual(cls, "beforeExitRequest", MethodType.methodType(void.class, int.class));
    mh[BootstrapDispatcher.BEFORE_REFLECTION_INVOKE] =
        lookup.findVirtual(
            cls,
            "beforeReflectionInvoke",
            MethodType.methodType(void.class, Object.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_DIRECT_BUFFER_ALLOCATE] =
        lookup.findVirtual(
            cls, "beforeDirectBufferAllocate", MethodType.methodType(void.class, int.class));
    mh[BootstrapDispatcher.BEFORE_OBJECT_DESERIALIZE] =
        lookup.findVirtual(
            cls, "beforeObjectDeserialize", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_CLASS_DEFINE] =
        lookup.findVirtual(
            cls,
            "beforeClassDefine",
            MethodType.methodType(void.class, Object.class, String.class));
    mh[BootstrapDispatcher.BEFORE_MONITOR_ENTER] =
        lookup.findVirtual(
            cls, "beforeMonitorEnter", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_PARK] =
        lookup.findVirtual(cls, "beforeThreadPark", MethodType.methodType(void.class));
    mh[BootstrapDispatcher.BEFORE_NIO_SELECT] =
        lookup.findVirtual(
            cls, "beforeNioSelect", MethodType.methodType(boolean.class, Object.class, long.class));
    mh[BootstrapDispatcher.BEFORE_NIO_CHANNEL_OP] =
        lookup.findVirtual(
            cls,
            "beforeNioChannelOp",
            MethodType.methodType(void.class, String.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_SOCKET_CONNECT] =
        lookup.findVirtual(
            cls,
            "beforeSocketConnect",
            MethodType.methodType(void.class, Object.class, Object.class, int.class));
    mh[BootstrapDispatcher.BEFORE_SOCKET_ACCEPT] =
        lookup.findVirtual(
            cls, "beforeSocketAccept", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_SOCKET_READ] =
        lookup.findVirtual(
            cls, "beforeSocketRead", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_SOCKET_WRITE] =
        lookup.findVirtual(
            cls, "beforeSocketWrite", MethodType.methodType(void.class, Object.class, int.class));
    mh[BootstrapDispatcher.BEFORE_SOCKET_CLOSE] =
        lookup.findVirtual(
            cls, "beforeSocketClose", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_JNDI_LOOKUP] =
        lookup.findVirtual(
            cls, "beforeJndiLookup", MethodType.methodType(void.class, Object.class, String.class));
    mh[BootstrapDispatcher.BEFORE_OBJECT_SERIALIZE] =
        lookup.findVirtual(
            cls,
            "beforeObjectSerialize",
            MethodType.methodType(void.class, Object.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_NATIVE_LIBRARY_LOAD] =
        lookup.findVirtual(
            cls, "beforeNativeLibraryLoad", MethodType.methodType(void.class, String.class));
    mh[BootstrapDispatcher.BEFORE_ASYNC_CANCEL] =
        lookup.findVirtual(
            cls,
            "beforeAsyncCancel",
            MethodType.methodType(boolean.class, Object.class, boolean.class));
    mh[BootstrapDispatcher.BEFORE_ZIP_INFLATE] =
        lookup.findVirtual(cls, "beforeZipInflate", MethodType.methodType(void.class));
    mh[BootstrapDispatcher.BEFORE_ZIP_DEFLATE] =
        lookup.findVirtual(cls, "beforeZipDeflate", MethodType.methodType(void.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_LOCAL_GET] =
        lookup.findVirtual(
            cls, "beforeThreadLocalGet", MethodType.methodType(boolean.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_LOCAL_SET] =
        lookup.findVirtual(
            cls,
            "beforeThreadLocalSet",
            MethodType.methodType(boolean.class, Object.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_JMX_INVOKE] =
        lookup.findVirtual(
            cls,
            "beforeJmxInvoke",
            MethodType.methodType(void.class, Object.class, Object.class, String.class));
    mh[BootstrapDispatcher.BEFORE_JMX_GET_ATTR] =
        lookup.findVirtual(
            cls,
            "beforeJmxGetAttr",
            MethodType.methodType(void.class, Object.class, Object.class, String.class));
    mh[BootstrapDispatcher.ADJUST_INSTANT_NOW] =
        lookup.findVirtual(
            cls,
            "adjustInstantNow",
            MethodType.methodType(java.time.Instant.class, java.time.Instant.class));
    mh[BootstrapDispatcher.ADJUST_LOCAL_DATE_TIME_NOW] =
        lookup.findVirtual(
            cls,
            "adjustLocalDateTimeNow",
            MethodType.methodType(java.time.LocalDateTime.class, java.time.LocalDateTime.class));
    mh[BootstrapDispatcher.ADJUST_ZONED_DATE_TIME_NOW] =
        lookup.findVirtual(
            cls,
            "adjustZonedDateTimeNow",
            MethodType.methodType(java.time.ZonedDateTime.class, java.time.ZonedDateTime.class));
    mh[BootstrapDispatcher.ADJUST_DATE_NEW] =
        lookup.findVirtual(cls, "adjustDateNew", MethodType.methodType(long.class, long.class));
    mh[BootstrapDispatcher.BEFORE_HTTP_SEND] =
        lookup.findVirtual(
            cls, "beforeHttpSend", MethodType.methodType(boolean.class, String.class));
    mh[BootstrapDispatcher.BEFORE_HTTP_SEND_ASYNC] =
        lookup.findVirtual(
            cls, "beforeHttpSendAsync", MethodType.methodType(boolean.class, String.class));
    mh[BootstrapDispatcher.BEFORE_JDBC_CONNECTION_ACQUIRE] =
        lookup.findVirtual(
            cls, "beforeJdbcConnectionAcquire", MethodType.methodType(boolean.class, String.class));
    mh[BootstrapDispatcher.BEFORE_JDBC_STATEMENT_EXECUTE] =
        lookup.findVirtual(
            cls, "beforeJdbcStatementExecute", MethodType.methodType(boolean.class, String.class));
    mh[BootstrapDispatcher.BEFORE_JDBC_PREPARED_STATEMENT] =
        lookup.findVirtual(
            cls, "beforeJdbcPreparedStatement", MethodType.methodType(boolean.class, String.class));
    mh[BootstrapDispatcher.BEFORE_JDBC_TRANSACTION_COMMIT] =
        lookup.findVirtual(
            cls, "beforeJdbcTransactionCommit", MethodType.methodType(boolean.class));
    mh[BootstrapDispatcher.BEFORE_JDBC_TRANSACTION_ROLLBACK] =
        lookup.findVirtual(
            cls, "beforeJdbcTransactionRollback", MethodType.methodType(boolean.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_SLEEP] =
        lookup.findVirtual(
            cls, "beforeThreadSleep", MethodType.methodType(boolean.class, long.class));
    mh[BootstrapDispatcher.BEFORE_DNS_RESOLVE] =
        lookup.findVirtual(
            cls, "beforeDnsResolve", MethodType.methodType(void.class, String.class));
    mh[BootstrapDispatcher.BEFORE_SSL_HANDSHAKE] =
        lookup.findVirtual(
            cls, "beforeSslHandshake", MethodType.methodType(void.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_FILE_IO] =
        lookup.findVirtual(
            cls, "beforeFileIo", MethodType.methodType(void.class, String.class, Object.class));
    return mh;
  }

  @FunctionalInterface
  private interface BuilderTransformer {
    net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
        net.bytebuddy.dynamic.DynamicType.Builder<?> builder);
  }

  /**
   * Conditionally instruments a JDK class that may not be present in all environments.
   *
   * <p>Before registering the type matcher, this method attempts to load the named class via the
   * system classloader. If the class is not found ({@link ClassNotFoundException}), the method logs
   * a fine-level message and returns the original {@code builder} unchanged so that a missing
   * optional dependency never aborts agent startup. If the class is present, a standard {@code
   * .type(...).transform(...)} entry is added to the builder.
   *
   * @param builder the current {@link AgentBuilder} chain to which the transformation may be
   *     appended
   * @param typeName fully-qualified binary name of the target class (e.g. {@code
   *     "javax.naming.InitialContext"})
   * @param transformer a {@link BuilderTransformer} that applies one or more {@link
   *     net.bytebuddy.asm.Advice} visits to the ByteBuddy type builder
   * @return the (possibly unchanged) {@link AgentBuilder} with the transformation appended when the
   *     class is present, or the original builder when it is absent
   */
  private static AgentBuilder instrumentOptional(
      final AgentBuilder builder, final String typeName, final BuilderTransformer transformer) {
    try {
      // Prefer the thread context classloader: OSGi containers and Spring Boot fat-JAR launchers
      // (LaunchedURLClassLoader) load application classes under a custom CL that is invisible to
      // the system classloader. Fall back to the system CL on startup threads where the context
      // CL may be null or may be the bootstrap CL (before the app CL is installed).
      final ClassLoader probe = Thread.currentThread().getContextClassLoader();
      Class.forName(typeName, false, probe != null ? probe : ClassLoader.getSystemClassLoader());
    } catch (final ClassNotFoundException ignored) {
      LOGGER.fine("[chaos-agent] optional instrumentation target not present: " + typeName);
      return builder;
    }
    return builder
        .type(ElementMatchers.named(typeName))
        .transform(
            (b, typeDescription, classLoader, module, protectionDomain) ->
                transformer.transform(b));
  }

  /**
   * Wires the {@link ChaosBridge} delegate into the bootstrap-loaded {@link
   * com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher}.
   *
   * <p>Because {@code BootstrapDispatcher} is loaded by the bootstrap classloader (after {@link
   * #injectBridge} appends it) and this class is loaded by the agent classloader, the two share no
   * common type. The method therefore resolves {@code BootstrapDispatcher} via {@link
   * Class#forName(String, boolean, ClassLoader)} with a {@code null} classloader argument
   * (bootstrap) and invokes its {@code install(Object, MethodHandle[])} method reflectively. The
   * {@link MethodHandle} array is built fresh by {@link #buildMethodHandles} for each call.
   *
   * @param bridgeDelegate the {@link ChaosBridge} instance (cast to {@link Object} because the
   *     bootstrap classloader cannot see the {@link ChaosBridge} type directly) that receives all
   *     dispatched calls
   * @throws IllegalStateException if {@link #buildMethodHandles} fails or if the reflective
   *     invocation of {@code BootstrapDispatcher.install} cannot be completed
   */
  private static void installDelegate(final Object bridgeDelegate) {
    try {
      final MethodHandle[] mh = buildMethodHandles();
      // Use reflection to call install on the bootstrap CL version of BootstrapDispatcher
      final Class<?> bootstrapDispatcher =
          Class.forName("com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher", true, null);
      bootstrapDispatcher
          .getMethod("install", Object.class, MethodHandle[].class)
          .invoke(null, bridgeDelegate, mh);
    } catch (final Exception exception) {
      throw new IllegalStateException("failed to install bridge delegate", exception);
    }
  }

  /**
   * Packages the bootstrap bridge classes into a temporary JAR and appends it to the bootstrap
   * classpath.
   *
   * <p>The classes written are:
   *
   * <ul>
   *   <li>{@code com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.class}
   *   <li>{@code
   *       com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher$ThrowingSupplier.class}
   * </ul>
   *
   * <p>Both are read from the agent classloader's resources (the same JAR that contains this class)
   * and written verbatim into the temp JAR. The temp file is registered for deletion on JVM exit.
   * After the JAR is written, {@link Instrumentation#appendToBootstrapClassLoaderSearch} makes its
   * contents visible to the bootstrap classloader, allowing instrumented JDK classes — which are
   * loaded by the bootstrap classloader — to call {@code BootstrapDispatcher} static methods.
   *
   * @param instrumentation the {@link Instrumentation} handle used to append to the bootstrap
   *     classpath
   * @throws IllegalStateException wrapping the underlying {@link java.io.IOException} if the
   *     temporary JAR cannot be created, if a required class resource is missing from the agent
   *     JAR, or if appending to the bootstrap classpath fails
   */
  private static void injectBridge(final Instrumentation instrumentation) {
    // Idempotency: appendToBootstrapClassLoaderSearch has no unwind operation — once a JAR
    // is appended, its entries stay visible to the bootstrap classloader for the JVM's
    // lifetime, and the bootstrap classloader caches the classes it has loaded. A second
    // install() call (dynamic-attach test runners that cycle install→uninstall→install)
    // that rebuilds and re-appends a fresh temp JAR would:
    //   (1) leak file descriptors in BRIDGE_JARS on every cycle,
    //   (2) shadow the already-loaded BootstrapDispatcher with a new Class object on a
    //       newer classpath entry for any class still resolvable lazily (inner classes),
    // neither of which is desirable. The bridge classes are fixed bytecode — once injected,
    // they never change — so skipping subsequent calls is both safe and correct.
    if (!BRIDGE_INJECTED.compareAndSet(false, true)) {
      return;
    }
    try {
      // On POSIX filesystems create with 0600: the bridge JAR contains the code that bootstrap
      // classloader will load, so a world-readable/writable file (the default 0644 umask-adjusted
      // file) is a privilege-escalation hazard — any local user who can write to /tmp could swap
      // the file before the JVM opens it and run arbitrary bootstrap-level code. On non-POSIX
      // filesystems (e.g. Windows) fall back to the default because PosixFileAttributes are
      // unsupported there; other platform-specific ACLs would have to be layered in separately.
      final Path bridgeJar = createSecureTempFile();
      bridgeJar.toFile().deleteOnExit();
      try (JarOutputStream jarOutputStream =
          new JarOutputStream(Files.newOutputStream(bridgeJar))) {
        writeClass(
            jarOutputStream, "com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.class");
        writeClass(
            jarOutputStream,
            "com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher$ThrowingSupplier.class");
        writeClass(
            jarOutputStream, "com/macstab/chaos/instrumentation/ScheduledRunnableWrapper.class");
        writeClass(
            jarOutputStream, "com/macstab/chaos/instrumentation/ScheduledCallableWrapper.class");
      }
      // Retain the JarFile in a static field. appendToBootstrapClassLoaderSearch does not
      // document whether the JVM keeps a strong reference to the Java-side JarFile; on
      // HotSpot the underlying native classpath entry is kept open, but the Java wrapper
      // becomes unreachable as soon as this method returns. If the JarFile is finalized and
      // its ZipFile closed, later bootstrap loads of ScheduledRunnableWrapper /
      // ScheduledCallableWrapper / BootstrapDispatcher classes can fail with
      // ZipException("ZipFile closed") at the first intercepted schedule() call — hours or
      // days into an otherwise-healthy run. Holding the reference is cheap (one file handle
      // per JVM) and eliminates the finalizer race. Note that BRIDGE_JARS is NEVER cleared
      // on uninstall — the bootstrap classloader has already loaded classes from the JAR and
      // releasing the JarFile would let its ZipFile finalize, breaking every future class
      // load that resolves lazily through the bootstrap classpath entry.
      final java.util.jar.JarFile jarFile = new java.util.jar.JarFile(bridgeJar.toFile());
      BRIDGE_JARS.add(jarFile);
      instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
    } catch (final IOException exception) {
      // Failure path: clear the injected flag so a subsequent install() can retry rather
      // than silently returning success when the bridge was never actually in place.
      BRIDGE_INJECTED.set(false);
      throw new IllegalStateException("failed to inject bootstrap bridge", exception);
    }
  }

  /**
   * Tracks whether {@link #injectBridge} has successfully installed the bootstrap bridge JAR.
   * JVM-wide single-shot because {@code Instrumentation.appendToBootstrapClassLoaderSearch} has no
   * reverse operation.
   */
  private static final java.util.concurrent.atomic.AtomicBoolean BRIDGE_INJECTED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Strong-reference holder for the injected bridge {@link java.util.jar.JarFile}. See the call
   * site above for the rationale.
   */
  private static final java.util.List<java.util.jar.JarFile> BRIDGE_JARS =
      java.util.Collections.synchronizedList(new java.util.ArrayList<>());

  private static Path createSecureTempFile() throws IOException {
    final String tmpDir = System.getProperty("java.io.tmpdir");
    final Path tmpPath = Path.of(tmpDir);
    if (Files.getFileStore(tmpPath).supportsFileAttributeView("posix")) {
      final java.nio.file.attribute.FileAttribute<?> ownerOnly =
          java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      return Files.createTempFile(BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX, ownerOnly);
    }
    return Files.createTempFile(BRIDGE_JAR_PREFIX, BRIDGE_JAR_SUFFIX);
  }

  private static void writeClass(final JarOutputStream jarOutputStream, final String resourcePath)
      throws IOException {
    final JarEntry jarEntry = new JarEntry(resourcePath);
    jarOutputStream.putNextEntry(jarEntry);
    try (InputStream inputStream =
        JdkInstrumentationInstaller.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IOException("missing bridge resource " + resourcePath);
      }
      inputStream.transferTo(jarOutputStream);
    }
    jarOutputStream.closeEntry();
  }
}
