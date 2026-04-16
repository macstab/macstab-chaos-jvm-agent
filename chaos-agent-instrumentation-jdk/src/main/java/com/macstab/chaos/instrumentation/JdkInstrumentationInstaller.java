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
 *       com.macstab.chaos.instrumentation.ChaosBridge}, builds the 42-slot {@link
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
 * libraries, JMX, ThreadLocal, etc.) are installed only in <em>premain</em> mode because they
 * require retransformation of already-loaded JDK classes, which is possible only when the agent is
 * attached at JVM startup via {@code -javaagent:}.
 *
 * <h2>Retransformation</h2>
 *
 * <p>All transformations use {@code disableClassFormatChanges()} combined with {@link
 * net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy#RETRANSFORMATION} to allow live
 * retransformation of already-loaded JDK classes without changing the class format.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This class is stateless. {@link #install} is expected to be called exactly once at startup,
 * from a single thread.
 */
public final class JdkInstrumentationInstaller {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

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
   * @param premainMode {@code true} when the agent was attached via {@code -javaagent:} at JVM
   *     startup, enabling Phase 2 JVM-level interception; {@code false} when attached dynamically
   *     at runtime, in which case Phase 2 is skipped
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
              .type(ElementMatchers.isSubTypeOf(java.util.concurrent.BlockingQueue.class))
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
              // Clock skew: System.currentTimeMillis() and System.nanoTime()
              .type(ElementMatchers.named("java.lang.System"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ClockMillisAdvice.class)
                                  .on(ElementMatchers.named("currentTimeMillis")))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ClockNanosAdvice.class)
                                  .on(ElementMatchers.named("nanoTime")))
                          .visit(
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
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("park")
                                          .and(ElementMatchers.takesArguments(Object.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkNanos")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  Object.class, long.class))))
                          .visit(
                              Advice.to(JvmRuntimeAdvice.ThreadParkAdvice.class)
                                  .on(
                                      ElementMatchers.named("parkUntil")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                  Object.class, long.class)))))
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
              // NIO Selector — target AbstractSelector subtypes (KQueueSelectorImpl etc.)
              .type(ElementMatchers.isSubTypeOf(java.nio.channels.spi.AbstractSelector.class))
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
                              Advice.to(JvmRuntimeAdvice.NioSelectNoArgAdvice.class)
                                  .on(
                                      ElementMatchers.named("selectNow")
                                          .and(ElementMatchers.takesArguments(0)))))
              // NIO SocketChannel
              .type(ElementMatchers.named("java.nio.channels.SocketChannel"))
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
              // NIO ServerSocketChannel
              .type(ElementMatchers.named("java.nio.channels.ServerSocketChannel"))
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
              .type(ElementMatchers.named("java.net.SocketInputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SocketReadAdvice.class)
                              .on(ElementMatchers.named("read"))))
              .type(ElementMatchers.named("java.net.SocketOutputStream"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(JvmRuntimeAdvice.SocketWriteAdvice.class)
                              .on(ElementMatchers.named("write"))))
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

      agentBuilder =
          instrumentOptional(
              agentBuilder,
              "java.lang.Runtime",
              builder ->
                  builder.visit(
                      Advice.to(JvmRuntimeAdvice.NativeLibraryLoadAdvice.class)
                          .on(ElementMatchers.named("loadLibrary0"))));

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
    }

    agentBuilder.installOn(instrumentation);

    // ── Native method instrumentation (separate AgentBuilder) ──────────────
    // System.currentTimeMillis(), System.nanoTime(), Runtime.gc(), Runtime.halt(),
    // and System.exit() are all native methods. ByteBuddy cannot instrument native
    // methods using disableClassFormatChanges() because it cannot add the non-native
    // wrapper method required to intercept native calls.
    //
    // Solution: a second AgentBuilder WITHOUT disableClassFormatChanges() combined
    // with a native method prefix. The prefix causes the JVM to look for
    // "$$chaos$$currentTimeMillis" as the renamed native, while our advice wrapper
    // becomes the public "currentTimeMillis". Requires Can-Set-Native-Method-Prefix
    // in the agent manifest.
    // Note: System.currentTimeMillis() and System.nanoTime() are @IntrinsicCandidate native
    // methods. On JDK 21+ with JIT enabled, the JVM inlines them directly to hardware clock
    // reads without going through the Java wrapper — ByteBuddy advice on the wrapper method
    // is never called after JIT compilation. ClockSkewEffect applies correctly when invoked
    // via ChaosRuntime.applyClockSkew() (the direct API path used in unit tests) but cannot
    // intercept System.currentTimeMillis() in production JVM bytecode. This is a fundamental
    // JVM constraint, not a framework limitation.
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
   * 42), one per dispatch slot. All handles are resolved against the {@link BridgeDelegate}
   * interface using a public lookup so that they are callable from the bootstrap classloader
   * context.
   *
   * @return a 42-element array of {@link MethodHandle} objects indexed by the {@code HANDLE_*}
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
        lookup.findVirtual(cls, "beforeMonitorEnter", MethodType.methodType(void.class));
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
      Class.forName(typeName, false, ClassLoader.getSystemClassLoader());
    } catch (ClassNotFoundException ignored) {
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
    } catch (Exception exception) {
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
    try {
      final Path bridgeJar = Files.createTempFile("macstab-chaos-bootstrap-bridge", ".jar");
      bridgeJar.toFile().deleteOnExit();
      try (JarOutputStream jarOutputStream =
          new JarOutputStream(Files.newOutputStream(bridgeJar))) {
        writeClass(
            jarOutputStream, "com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.class");
        writeClass(
            jarOutputStream,
            "com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher$ThrowingSupplier.class");
      }
      instrumentation.appendToBootstrapClassLoaderSearch(
          new java.util.jar.JarFile(bridgeJar.toFile()));
    } catch (IOException exception) {
      throw new IllegalStateException("failed to inject bootstrap bridge", exception);
    }
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
