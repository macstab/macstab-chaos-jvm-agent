package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.core.HookDemand;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.BridgeDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.jspecify.annotations.Nullable;

/**
 * Static-only installer that wires ByteBuddy instrumentation for all JDK interception points.
 *
 * <h2>Startup sequence</h2>
 *
 * <ol>
 *   <li>{@link #install} is called once by {@code ChaosAgentBootstrap} after the runtime is ready.
 *   <li>{@link #injectBridge(java.lang.instrument.Instrumentation)} packages {@link
 *       com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher} and the types woven
 *       advice references into a temporary JAR and appends it to the bootstrap classpath so that
 *       bootstrap-loaded JDK classes can see it.
 *   <li>{@link #installDelegate(Object)} constructs a {@link
 *       com.macstab.chaos.jvm.instrumentation.ChaosBridge}, builds the {@link
 *       com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher#HANDLE_COUNT
 *       HANDLE_COUNT}-slot {@link java.lang.invoke.MethodHandle} array via {@link
 *       #buildMethodHandles}, and calls the bootstrap-loaded {@code BootstrapDispatcher.install()}
 *       via reflection to wire the bridge.
 *   <li>ByteBuddy's {@code AgentBuilder} is assembled with one transformation per interception
 *       point and installed via {@link
 *       net.bytebuddy.agent.builder.AgentBuilder#installOn(java.lang.instrument.Instrumentation)}.
 * </ol>
 *
 * <h2>On-demand weaving</h2>
 *
 * <p>Every interception point belongs to a {@link HookGroup} tagged with the operations it serves.
 * A group is woven the first time a scenario for one of its operations is activated (or, for the
 * executor groups, when a session is opened) and stays woven until {@link #uninstall}. A JVM with
 * the agent attached but no scenario carries no advice in its JDK classes. {@code eager} weaves
 * every group at install time. The same path serves static attach ({@code -javaagent:}), dynamic
 * attach and self-attach: the installer relies on ByteBuddy's {@code
 * RedefinitionStrategy.RETRANSFORMATION} combined with {@code disableClassFormatChanges()} to
 * rewrite already-loaded JDK classes such as {@code java.net.Socket}.
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
  private static final Map<HookGroup, ResettableClassFileTransformer> WOVEN_GROUPS =
      new java.util.EnumMap<>(HookGroup.class);

  private JdkInstrumentationInstaller() {}

  /**
   * Installs the agent into the JVM: grants the module opens the advice needs, injects the
   * bootstrap bridge and wires the {@link ChaosBridge} delegate to {@code runtime}.
   *
   * <p>Hooks are woven on demand: the runtime calls back into {@link #weave} before a scenario that
   * needs a hook group starts (and when a session opens, for executor task decoration), so a JVM
   * without scenarios carries no advice in its JDK classes. The same path serves {@code premain},
   * {@code agentmain} and self-attach; dynamic attach is no longer limited to a subset of hooks.
   *
   * @param instrumentation the {@link Instrumentation} handle provided by the JVM at agent
   *     attachment
   * @param runtime the runtime the bridge dispatches to
   * @param eager {@code true} to weave every hook group immediately instead of on demand (the 1.x
   *     behaviour; useful when activation must not pay the one-time weaving cost)
   */
  public static void install(
      final Instrumentation instrumentation, final ChaosRuntime runtime, final boolean eager) {
    install(instrumentation, runtime, eager, List.of());
  }

  /**
   * Installs the agent, with clock call-site weaving for the classes under {@code
   * clockCallSitePackages}: there, calls to {@link System#currentTimeMillis()} and {@link
   * System#nanoTime()} are rewritten to go through the clock-skew dispatch once a {@code
   * SYSTEM_CLOCK_MILLIS} or {@code SYSTEM_CLOCK_NANOS} scenario is activated. The methods
   * themselves are JIT intrinsics no advice can reach; their call sites can be rewritten.
   *
   * @param instrumentation the {@link Instrumentation} handle
   * @param runtime the runtime the bridge dispatches to
   * @param eager {@code true} to weave every hook group immediately
   * @param clockCallSitePackages package (or class-name) prefixes whose clock calls are rewritten,
   *     e.g. {@code com.example.}; {@code java.}, {@code javax.}, {@code jdk.}, {@code sun.} and
   *     {@code com.sun.} prefixes are refused
   * @throws IllegalArgumentException if a prefix names JDK classes
   */
  public static void install(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean eager,
      final List<String> clockCallSitePackages) {
    install(instrumentation, runtime, eager, clockCallSitePackages, List.of());
  }

  /**
   * Installs the agent with clock call-site weaving and additional session-header hosts.
   *
   * @param instrumentation the {@link Instrumentation} handle
   * @param runtime the runtime the bridge dispatches to
   * @param eager {@code true} to weave every hook group immediately
   * @param clockCallSitePackages package prefixes whose clock calls are rewritten
   * @param sessionHeaderHosts host globs that receive {@code X-Chaos-Session} besides the internal
   *     hosts ({@code *} for all)
   */
  public static void install(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean eager,
      final List<String> clockCallSitePackages,
      final List<String> sessionHeaderHosts) {
    CLOCK_CALL_SITE_PACKAGES = validatedClockPackages(clockCallSitePackages);
    SESSION_HEADER_HOSTS = List.copyOf(sessionHeaderHosts);
    grantInternalModuleOpens(instrumentation);
    injectBridge(instrumentation);
    rejectForeignDelegate();
    installDelegate(new ChaosBridge(runtime));
    com.macstab.chaos.jvm.instrumentation.bridge.SessionHeaderPolicy.setExtraHosts(
        SESSION_HEADER_HOSTS);
    try {
      runtime.setHookDemand(
          new HookDemand() {
            @Override
            public void require(final Set<OperationType> operations) {
              weave(instrumentation, operations);
            }

            @Override
            public Set<String> woven() {
              final Set<String> names = new java.util.TreeSet<>();
              for (final HookGroup group : wovenGroups()) {
                names.add(group.name());
              }
              if (MethodWeaver.active()) {
                names.add("METHODS");
              }
              return names;
            }

            @Override
            public Set<String> unmatched() {
              return HookCoverage.unmatched();
            }

            @Override
            public Set<String> failures() {
              return HookCoverage.failures();
            }

            @Override
            public void methods(final Set<ChaosSelector.MethodSelector> selectors) {
              MethodWeaver.weave(
                  instrumentation, selectors, JdkInstrumentationInstaller::buildBaseAgentBuilder);
            }
          });
      if (eager) {
        weave(instrumentation, EnumSet.allOf(OperationType.class));
      }
    } catch (final RuntimeException | Error failure) {
      // Leave nothing bound to this runtime: a later install attempt must start from a clean
      // dispatcher, and the instrumented JDK classes must not call into a runtime that never
      // became active.
      uninstall(instrumentation);
      throw failure;
    }
  }

  /**
   * Weaves the hook groups serving {@code operations} that are not woven yet. Each group is its own
   * {@link AgentBuilder} installation, so it retransforms only the classes it targets and can be
   * reset on its own. Groups stay woven until {@link #uninstall}: re-weaving on every activation
   * and stop would retransform core JDK classes over and over in test suites.
   *
   * @param instrumentation the instrumentation handle
   * @param operations the operations about to be evaluated
   * @throws IllegalStateException if a group cannot be woven
   */
  static void weave(final Instrumentation instrumentation, final Set<OperationType> operations) {
    synchronized (WOVEN_GROUPS) {
      boolean wovenNow = false;
      for (final HookGroup group : HookGroup.values()) {
        if (WOVEN_GROUPS.containsKey(group) || !group.serves(operations)) {
          continue;
        }
        final ResettableClassFileTransformer transformer;
        try {
          transformer = group.apply(buildBaseAgentBuilder()).installOn(instrumentation);
        } catch (final RuntimeException failure) {
          throw new IllegalStateException(
              "failed to instrument " + group + " for " + group.operations(), failure);
        }
        WOVEN_GROUPS.put(group, transformer);
        wovenNow = true;
        LOGGER.fine(() -> "chaos hooks woven: " + group);
      }
      // Some JDK classes (the object streams, the socket classes) can be loaded as a side effect
      // of an installOn() while it is inside a JVMTI ClassFileLoadHook for another class; the
      // JVMTI re-entrancy rule then suppresses the hook for those loads, so they escape every
      // transformer, also those of groups woven earlier. Outside any hook, an explicit
      // retransformation applies the missing advice; for a class no group targets it is a no-op.
      if (wovenNow) {
        retransformPostInstallEscapees(instrumentation);
      }
    }
  }

  /**
   * Returns the hooks that matched a method of their type, as {@code advice on matcher}: what the
   * hook-coverage test checks against every advice class on the running JDK.
   *
   * @return the active hooks, sorted
   */
  public static Set<String> matchedHooks() {
    return HookCoverage.matched();
  }

  /** Returns the hook groups woven so far; for diagnostics and tests. */
  static Set<HookGroup> wovenGroups() {
    synchronized (WOVEN_GROUPS) {
      return WOVEN_GROUPS.isEmpty() ? Set.of() : EnumSet.copyOf(WOVEN_GROUPS.keySet());
    }
  }

  /**
   * The groups of JDK and library hooks, each with the operations it serves. A group is woven the
   * first time a scenario for one of its operations is activated.
   */
  enum HookGroup {
    THREAD_START(
        JdkInstrumentationInstaller::applyThreadStartTransformations,
        OperationType.THREAD_START,
        OperationType.VIRTUAL_THREAD_START),
    THREAD_POOL_EXECUTOR(
        JdkInstrumentationInstaller::applyThreadPoolExecutorTransformations,
        OperationType.EXECUTOR_SUBMIT,
        OperationType.EXECUTOR_WORKER_RUN,
        OperationType.EXECUTOR_SHUTDOWN,
        OperationType.EXECUTOR_AWAIT_TERMINATION),
    SCHEDULED_EXECUTOR(
        JdkInstrumentationInstaller::applyScheduledExecutorTransformations,
        OperationType.SCHEDULE_SUBMIT,
        OperationType.SCHEDULE_TICK),
    FORK_JOIN(
        JdkInstrumentationInstaller::applyForkJoinTransformations,
        OperationType.FORK_JOIN_TASK_RUN),
    BLOCKING_QUEUE(
        JdkInstrumentationInstaller::applyBlockingQueueTransformations,
        OperationType.QUEUE_PUT,
        OperationType.QUEUE_OFFER,
        OperationType.QUEUE_TAKE,
        OperationType.QUEUE_POLL),
    COMPLETABLE_FUTURE(
        JdkInstrumentationInstaller::applyCompletableFutureTransformations,
        OperationType.ASYNC_COMPLETE,
        OperationType.ASYNC_COMPLETE_EXCEPTIONALLY),
    COMPLETABLE_FUTURE_CANCEL(
        JdkInstrumentationInstaller::applyCompletableFutureCancelTransformations,
        OperationType.ASYNC_CANCEL),
    SHUTDOWN_HOOK(
        JdkInstrumentationInstaller::applyShutdownHookTransformations,
        OperationType.SHUTDOWN_HOOK_REGISTER),
    CLASS_LOADER(
        JdkInstrumentationInstaller::applyClassLoaderTransformations,
        OperationType.CLASS_LOAD,
        OperationType.RESOURCE_LOAD),
    CLASS_DEFINE(
        JdkInstrumentationInstaller::applyClassDefineTransformations, OperationType.CLASS_DEFINE),
    RUNTIME_LIFECYCLE(
        JdkInstrumentationInstaller::applyRuntimeLifecycleTransformations,
        OperationType.SYSTEM_GC_REQUEST,
        OperationType.SYSTEM_EXIT_REQUEST),
    REFLECTION_INVOKE(
        JdkInstrumentationInstaller::applyReflectionInvokeTransformations,
        OperationType.REFLECTION_INVOKE),
    DIRECT_BUFFER(
        JdkInstrumentationInstaller::applyDirectBufferTransformations,
        OperationType.DIRECT_BUFFER_ALLOCATE),
    SERIALIZATION(
        JdkInstrumentationInstaller::applySerializationTransformations,
        OperationType.OBJECT_SERIALIZE,
        OperationType.OBJECT_DESERIALIZE),
    THREAD_PARK(
        JdkInstrumentationInstaller::applyLockSupportTransformations, OperationType.THREAD_PARK),
    MONITOR_ENTER(
        JdkInstrumentationInstaller::applyAqsMonitorTransformations, OperationType.MONITOR_ENTER),
    NIO(
        JdkInstrumentationInstaller::applyNioChannelTransformations,
        OperationType.NIO_SELECTOR_SELECT,
        OperationType.NIO_CHANNEL_READ,
        OperationType.NIO_CHANNEL_WRITE,
        OperationType.NIO_CHANNEL_CONNECT,
        OperationType.NIO_CHANNEL_ACCEPT),
    SOCKET(
        JdkInstrumentationInstaller::applySocketAndStreamTransformations,
        OperationType.SOCKET_CONNECT,
        OperationType.SOCKET_ACCEPT,
        OperationType.SOCKET_READ,
        OperationType.SOCKET_WRITE,
        OperationType.SOCKET_CLOSE,
        OperationType.SOCKET_BIND,
        OperationType.SOCKET_SHUTDOWN),
    /** UDP: datagram sends and receives of {@code DatagramSocket} and {@code DatagramChannel}. */
    DATAGRAM(
        JdkInstrumentationInstaller::applyDatagramTransformations,
        OperationType.DATAGRAM_SEND,
        OperationType.DATAGRAM_RECEIVE),
    /** The same socket events, raised by {@code SocketChannel} and {@code ServerSocketChannel}. */
    SOCKET_CHANNEL(
        JdkInstrumentationInstaller::applySocketChannelTransformations,
        OperationType.SOCKET_CONNECT,
        OperationType.SOCKET_ACCEPT,
        OperationType.SOCKET_READ,
        OperationType.SOCKET_WRITE,
        OperationType.SOCKET_CLOSE,
        OperationType.SOCKET_SHUTDOWN),
    ZIP(
        JdkInstrumentationInstaller::applyZipTransformations,
        OperationType.ZIP_INFLATE,
        OperationType.ZIP_DEFLATE),
    THREAD_LOCAL(
        JdkInstrumentationInstaller::applyThreadLocalTransformations,
        OperationType.THREAD_LOCAL_GET,
        OperationType.THREAD_LOCAL_SET),
    CLOCK_CALL_SITES(
        JdkInstrumentationInstaller::applyClockCallSiteTransformations,
        OperationType.SYSTEM_CLOCK_MILLIS,
        OperationType.SYSTEM_CLOCK_NANOS),
    TIME_API(
        JdkInstrumentationInstaller::applyTimeApiTransformations,
        OperationType.INSTANT_NOW,
        OperationType.CLOCK_READ,
        OperationType.LOCAL_DATE_TIME_NOW,
        OperationType.ZONED_DATE_TIME_NOW,
        OperationType.DATE_NEW),
    SSL_HANDSHAKE(
        JdkInstrumentationInstaller::applySslHandshakeTransformations, OperationType.SSL_HANDSHAKE),
    KAFKA(
        JdkInstrumentationInstaller::applyKafkaTransformations,
        OperationType.MESSAGE_SEND,
        OperationType.MESSAGE_RECEIVE),
    GRPC(JdkInstrumentationInstaller::applyGrpcTransformations, OperationType.RPC_CALL),
    REDIS(JdkInstrumentationInstaller::applyRedisTransformations, OperationType.REDIS_COMMAND),
    R2DBC(
        JdkInstrumentationInstaller::applyR2dbcTransformations,
        OperationType.R2DBC_STATEMENT_EXECUTE),
    AWS_SDK(JdkInstrumentationInstaller::applyAwsSdkTransformations, OperationType.AWS_SDK_CALL),
    NETTY(
        JdkInstrumentationInstaller::applyNettyTransformations,
        OperationType.NETTY_CHANNEL_WRITE,
        OperationType.NETTY_CHANNEL_READ),
    HTTP_CLIENT(
        JdkInstrumentationInstaller::applyPhase2HttpClientInterception,
        OperationType.HTTP_CLIENT_SEND,
        OperationType.HTTP_CLIENT_SEND_ASYNC),
    JDBC(
        JdkInstrumentationInstaller::applyPhase2JdbcInterception,
        OperationType.JDBC_CONNECTION_ACQUIRE,
        OperationType.JDBC_STATEMENT_EXECUTE,
        OperationType.JDBC_PREPARED_STATEMENT,
        OperationType.JDBC_TRANSACTION_COMMIT,
        OperationType.JDBC_TRANSACTION_ROLLBACK),
    JNDI_JMX(
        JdkInstrumentationInstaller::applyPhase2JndiAndJmxInterception,
        OperationType.JNDI_LOOKUP,
        OperationType.JMX_INVOKE,
        OperationType.JMX_GET_ATTR),
    NATIVE_LIBRARY(
        JdkInstrumentationInstaller::applyPhase2NativeLibraryLoadInterception,
        OperationType.NATIVE_LIBRARY_LOAD),
    THREAD_SLEEP(
        JdkInstrumentationInstaller::applyPhase2ThreadSleepInterception,
        OperationType.THREAD_SLEEP),
    DNS(
        JdkInstrumentationInstaller::applyPhase2DnsResolutionInterception,
        OperationType.DNS_RESOLVE,
        OperationType.DNS_ANSWER,
        OperationType.DNS_REVERSE),
    PROCESS(
        JdkInstrumentationInstaller::applyProcessInterception,
        OperationType.PROCESS_START,
        OperationType.PROCESS_WAIT),
    FILE_IO(
        JdkInstrumentationInstaller::applyPhase2FileIoInterception,
        OperationType.FILE_IO_READ,
        OperationType.FILE_IO_WRITE,
        OperationType.FILE_IO_SYNC,
        OperationType.FILE_IO_TRUNCATE,
        OperationType.FILE_IO_DELETE,
        OperationType.FILE_IO_MOVE,
        OperationType.FILE_IO_OPEN,
        OperationType.FILE_IO_MAP,
        OperationType.FILE_IO_CLOSE,
        OperationType.FILE_IO_MOVE_TARGET);

    // Immutable in fact: a stateless function and an unmodifiable set.
    @SuppressWarnings("ImmutableEnumChecker")
    private final java.util.function.UnaryOperator<AgentBuilder> transformations;

    @SuppressWarnings("ImmutableEnumChecker")
    private final Set<OperationType> operations;

    HookGroup(
        final java.util.function.UnaryOperator<AgentBuilder> transformations,
        final OperationType first,
        final OperationType... rest) {
      this.transformations = transformations;
      this.operations = Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    AgentBuilder apply(final AgentBuilder builder) {
      return transformations.apply(builder);
    }

    Set<OperationType> operations() {
      return operations;
    }

    boolean serves(final Set<OperationType> requested) {
      for (final OperationType operation : operations) {
        if (requested.contains(operation)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Self-grants reflective and instrumentation access to the JDK internal packages this agent needs
   * to function, without requiring the user to set {@code --add-opens} JVM flags.
   *
   * <p>The agent JAR's {@code MANIFEST.MF} carries a matching {@code Add-Opens} attribute (see
   * {@code chaos-agent-bootstrap/build.gradle.kts}) that the JVM honours on the {@code -javaagent:}
   * (premain) attach path before {@code premain} runs. Manifest opens are <b>not</b> honoured for
   * {@code agentmain} or runtime self-attach via {@code ByteBuddyAgent.install()}, so for those
   * paths this method calls {@link Instrumentation#redefineModule} programmatically — exposing the
   * same opens to the unnamed module of the system class loader. Same pattern OpenTelemetry,
   * Datadog, and New Relic agents use.
   *
   * <p>Each entry below is paired with the agent feature that depends on it. If a future feature
   * needs a new internal package, add it both to the manifest <i>and</i> to this list.
   *
   * <p>Failures are tolerated and logged at FINE level: the JVM may legitimately deny {@code
   * redefineModule} on stripped or future runtimes. Downstream code that depends on the open
   * already handles {@code java.lang.reflect.InaccessibleObjectException} via try/catch (see {@code
   * HttpUrlExtractor}, {@code JdbcTargetExtractor}, {@code DirectBufferPressureStressor}).
   *
   * @param instrumentation the JVM-supplied {@link Instrumentation} handle, used for the {@code
   *     redefineModule} call
   */
  private static void grantInternalModuleOpens(final Instrumentation instrumentation) {
    final Module unnamed = ClassLoader.getSystemClassLoader().getUnnamedModule();
    final Set<Module> openTo = Set.of(unnamed);

    // moduleClass → packages to open. Loaded reflectively so this code stays compilable on
    // toolchains that expose only some of these modules. Each lookup tolerates an absent module.
    final List<String[]> opens =
        List.of(
            new String[] {"java.net.http", "jdk.internal.net.http"},
            new String[] {"java.base", "jdk.internal.misc"},
            new String[] {"java.base", "jdk.internal.loader"},
            new String[] {"java.base", "sun.nio.ch"},
            new String[] {"java.base", "jdk.internal.ref"});

    for (final String[] entry : opens) {
      final String moduleName = entry[0];
      final String packageName = entry[1];
      final Module module = ModuleLayer.boot().findModule(moduleName).orElse(null);
      if (module == null) {
        LOGGER.log(
            Level.FINE,
            "module {0} not present; skipping open of {1}",
            new Object[] {moduleName, packageName});
        continue;
      }
      if (!module.getPackages().contains(packageName)) {
        LOGGER.log(
            Level.FINE,
            "module {0} does not contain package {1}; skipping",
            new Object[] {moduleName, packageName});
        continue;
      }
      try {
        final Map<String, Set<Module>> extraOpens = new LinkedHashMap<>();
        extraOpens.put(packageName, openTo);
        instrumentation.redefineModule(
            module,
            Collections.emptySet(), // extraReads
            Collections.emptyMap(), // extraExports
            extraOpens, // extraOpens
            Collections.emptySet(), // extraUses
            Collections.emptyMap()); // extraProvides
      } catch (final RuntimeException failure) {
        LOGGER.log(
            Level.FINE,
            failure,
            () ->
                "failed to redefineModule open for "
                    + moduleName
                    + "/"
                    + packageName
                    + " — chaos features depending on this open may degrade gracefully");
      }
    }
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
    final boolean delegateCleared = clearOwnDelegate();
    MethodWeaver.reset(instrumentation);
    synchronized (WOVEN_GROUPS) {
      if (WOVEN_GROUPS.isEmpty()) {
        return delegateCleared;
      }
      for (final ResettableClassFileTransformer transformer : WOVEN_GROUPS.values()) {
        transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
      }
      WOVEN_GROUPS.clear();
    }
    return true;
  }

  /**
   * Refuses to install when the JVM-wide dispatcher already carries a delegate from a copy of the
   * agent loaded by a different class loader (for example an agent jar on the system class path
   * plus a starter bundled child-first in a web application). Installing would replace that
   * delegate: the first copy's startup plan, scenarios and diagnostics would silently stop
   * receiving any call.
   */
  private static void rejectForeignDelegate() {
    final Object existing = installedDelegateOrNull();
    if (existing != null && !ownLoader(existing)) {
      throw new IllegalStateException(
          "the chaos agent is already installed in this JVM by another class loader ("
              + existing.getClass().getClassLoader()
              + "); only one copy of the agent can be active per JVM. Remove the duplicate"
              + " chaos-agent jar from the application, or use the copy that is already installed");
    }
  }

  /** Clears the dispatcher delegate when it belongs to this copy of the agent. */
  private static boolean clearOwnDelegate() {
    final Object existing = installedDelegateOrNull();
    if (existing == null || !ownLoader(existing)) {
      return false;
    }
    installDelegateRaw(null, null);
    return true;
  }

  private static @Nullable Object installedDelegateOrNull() {
    try {
      return bootstrapDispatcherClass().getMethod("installedDelegate").invoke(null);
    } catch (final ClassNotFoundException notInjected) {
      return null;
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException("failed to read the bridge delegate", exception);
    }
  }

  private static Class<?> bootstrapDispatcherClass() throws ClassNotFoundException {
    return Class.forName(
        "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher", true, null);
  }

  private static void installDelegateRaw(
      final @Nullable Object bridgeDelegate, final MethodHandle @Nullable [] methodHandles) {
    try {
      bootstrapDispatcherClass()
          .getMethod("install", Object.class, MethodHandle[].class)
          .invoke(null, bridgeDelegate, methodHandles);
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException("failed to install bridge delegate", exception);
    }
  }

  /**
   * Connects {@code runtime} to the {@link
   * com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher} without weaving any class, so
   * benchmarks and tests can drive the dispatch methods exactly as woven advice does. Closing the
   * result disconnects it again.
   *
   * @param runtime the runtime that receives the dispatches
   * @return a handle that uninstalls the dispatcher's delegate when closed
   * @throws Exception if the method handles cannot be built
   */
  public static AutoCloseable connectDispatcher(final ChaosRuntime runtime) throws Exception {
    com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher.install(
        new ChaosBridge(runtime), buildMethodHandles());
    return () ->
        com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher.install(null, null);
  }

  /**
   * Builds the fixed-size {@link MethodHandle} array that backs {@link
   * com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher}.
   *
   * <p>Each slot in the returned array corresponds to one of the {@code public static final int}
   * index constants declared on {@link
   * com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher} (e.g. {@code
   * ADJUST_CLOCK_MILLIS}, {@code BEFORE_GC_REQUEST}, etc.). The array has exactly {@link
   * com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher#HANDLE_COUNT} elements, one
   * per dispatch slot. All handles are unbound virtual handles resolved against the {@link
   * BridgeDelegate} interface via a public lookup; each dispatch call passes the delegate instance
   * as the first argument so handles remain callable from the bootstrap classloader context.
   *
   * @return an array of {@link MethodHandle} objects sized to {@link
   *     com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher#HANDLE_COUNT} and indexed
   *     by the {@code HANDLE_*} constants on {@link
   *     com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher}; no element is {@code
   *     null}
   * @throws Exception if any required method is absent from {@link BridgeDelegate} or if the lookup
   *     fails for any reason
   */
  static MethodHandle[] buildMethodHandles() throws Exception {
    // Slot FOO_BAR on BootstrapDispatcher is served by BridgeDelegate.fooBar: one table, checked
    // in full here, instead of a hand-written assignment per slot.
    final java.util.Map<String, java.lang.reflect.Method> methods = new java.util.HashMap<>();
    for (final java.lang.reflect.Method method : BridgeDelegate.class.getMethods()) {
      if (methods.put(method.getName(), method) != null) {
        throw new IllegalStateException("BridgeDelegate." + method.getName() + " is overloaded");
      }
    }
    final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    final MethodHandle[] methodHandles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    for (final java.lang.reflect.Field field : BootstrapDispatcher.class.getFields()) {
      if (!isHandleSlot(field)) {
        continue;
      }
      final String name = delegateMethodName(field.getName());
      final java.lang.reflect.Method method = methods.remove(name);
      if (method == null) {
        throw new IllegalStateException(
            "handle slot " + field.getName() + " has no BridgeDelegate." + name);
      }
      final int index = field.getInt(null);
      if (methodHandles[index] != null) {
        throw new IllegalStateException(
            "handle slot " + field.getName() + " reuses index " + index);
      }
      methodHandles[index] = lookup.unreflect(method);
    }
    if (!methods.isEmpty()) {
      throw new IllegalStateException(
          "BridgeDelegate methods without a handle slot: " + methods.keySet());
    }
    for (int index = 0; index < methodHandles.length; index++) {
      if (methodHandles[index] == null) {
        throw new IllegalStateException("no handle slot has index " + index);
      }
    }
    return methodHandles;
  }

  /** A {@code public static final int} slot constant: not a call-depth scope, not the count. */
  private static boolean isHandleSlot(final java.lang.reflect.Field field) {
    final int modifiers = field.getModifiers();
    return field.getType() == int.class
        && java.lang.reflect.Modifier.isStatic(modifiers)
        && java.lang.reflect.Modifier.isFinal(modifiers)
        && !field.getName().startsWith("SCOPE_")
        && !field.getName().equals("HANDLE_COUNT");
  }

  /** {@code BEFORE_SOCKET_READ} → {@code beforeSocketRead}. */
  static String delegateMethodName(final String slot) {
    final StringBuilder name = new StringBuilder();
    for (final String word : slot.toLowerCase(java.util.Locale.ROOT).split("_", -1)) {
      name.append(
          name.length() == 0 ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
    }
    return name.toString();
  }

  @FunctionalInterface
  private interface BuilderTransformer {
    net.bytebuddy.dynamic.DynamicType.Builder<?> transform(
        net.bytebuddy.dynamic.DynamicType.Builder<?> builder);
  }

  /**
   * Instruments classes that may not be present in every application (OkHttp, Apache HttpClient,
   * HikariCP, c3p0, Reactor Netty) or every JDK.
   *
   * <p>The type is matched by name only. ByteBuddy evaluates the matcher whenever a class is
   * defined, by any class loader, so a library loaded later by an application class loader (for
   * example Spring Boot's fat-jar launcher, which does not exist yet when {@code premain} runs) is
   * still woven. An absent library costs one string comparison per class definition and nothing
   * else. Advice applied this way only uses {@code Object}-typed parameters and reflective access,
   * so the libraries are not needed on the agent's classpath.
   *
   * <p>An earlier version probed for the class with {@code Class.forName} at install time and
   * skipped the transformation when the probe failed. Under {@code premain} in a fat jar the probe
   * always failed, so OkHttp, Apache HttpClient, HikariCP, c3p0 and Reactor Netty chaos silently
   * never applied.
   *
   * @param builder the current {@link AgentBuilder} chain
   * @param transformer a {@link BuilderTransformer} that applies one or more {@link
   *     net.bytebuddy.asm.Advice} visits to the ByteBuddy type builder
   * @param typeNames fully-qualified binary names of the target classes; several names cover a
   *     class that moved between library versions
   * @return the {@link AgentBuilder} with the transformation appended
   */
  private static AgentBuilder instrumentOptional(
      final AgentBuilder builder, final BuilderTransformer transformer, final String... typeNames) {
    return builder
        .type(ElementMatchers.namedOneOf(typeNames))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                transformer.transform(typeBuilder));
  }

  /**
   * Wires the {@link ChaosBridge} delegate into the bootstrap-loaded {@link
   * com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher}.
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
    final MethodHandle[] methodHandles;
    try {
      methodHandles = buildMethodHandles();
    } catch (final Exception exception) {
      throw new IllegalStateException("failed to build bridge method handles", exception);
    }
    // Reflection: install() lives on the bootstrap-loaded copy of BootstrapDispatcher.
    installDelegateRaw(bridgeDelegate, methodHandles);
  }

  /**
   * Packages the bootstrap bridge classes into a temporary JAR and appends it to the bootstrap
   * classpath.
   *
   * <p>The classes written are {@code BootstrapDispatcher} and the types that advice woven into JDK
   * classes references (the scheduled-task wrappers and the API exceptions it throws).
   *
   * <p>They are read from the agent classloader's resources (the same JAR that contains this class)
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
    boolean appended = false;
    try {
      // On POSIX filesystems create with 0600: the bridge JAR contains the code that bootstrap
      // classloader will load, so a world-readable/writable file (the default 0644 umask-adjusted
      // file) is a privilege-escalation hazard — any local user who can write to /tmp could swap
      // the file before the JVM opens it and run arbitrary bootstrap-level code. On non-POSIX
      // filesystems (e.g. Windows) fall back to the default because PosixFileAttributes are
      // unsupported there; other platform-specific ACLs would have to be layered in separately.
      sweepStaleBridgeJars();
      final Path bridgeJar = createSecureTempFile();
      bridgeJar.toFile().deleteOnExit();
      try (JarOutputStream jarOutputStream =
          new JarOutputStream(Files.newOutputStream(bridgeJar))) {
        for (final String bridgeClass : BRIDGE_CLASSES) {
          writeClass(jarOutputStream, bridgeClass);
        }
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
      appended = true;
      // The bootstrap class path entry is opened when it is appended, and the JarFile above holds
      // its own handle, so on POSIX the file can be unlinked now and nothing is left in the temp
      // directory, even when the JVM is killed. deleteOnExit alone left one jar per JVM start
      // behind after kill -9. Windows refuses to delete an open file: there the jar stays until
      // a later start sweeps it (see sweepStaleBridgeJars).
      try {
        Files.deleteIfExists(bridgeJar);
      } catch (final IOException inUse) {
        LOGGER.fine(() -> "bridge jar kept until exit: " + bridgeJar);
      }
    } catch (final IOException exception) {
      // Failure path: clear the injected flag so a subsequent install() can retry rather
      // than silently returning success when the bridge was never actually in place.
      BRIDGE_INJECTED.set(false);
      throw new IllegalStateException("failed to inject bootstrap bridge", exception);
    } catch (final RuntimeException | Error failure) {
      // The same for an unchecked failure, unless the jar is already on the bootstrap class path:
      // that cannot be undone, and appending a second copy is what the flag prevents.
      if (!appended) {
        BRIDGE_INJECTED.set(false);
      }
      throw failure;
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

  /**
   * Names of classes that are reliably loaded as a side-effect of {@link AgentBuilder#installOn}
   * itself — usually because ByteBuddy's own machinery touches them while it is already inside a
   * JVMTI {@code ClassFileLoadHook} for some other type, and the hook is not re-entrant. Any
   * matcher that should fire on these classes has to be applied via an explicit post-install {@code
   * retransformClasses} call (the JVMTI re-entrancy rule no longer applies once {@code installOn}
   * has returned).
   */
  private static final String[] POST_INSTALL_RETRANSFORM_TARGETS = {
    "java.io.ObjectOutputStream",
    "java.io.ObjectInputStream",
    // VirtualThread overrides Thread.start(); a Thread-only weave silently misses every
    // VIRTUAL_THREAD_START event because vt.start() dispatches to VirtualThread.start() (its
    // own override), bypassing Thread.start() entirely. We weave VirtualThread.start() above,
    // but the class is loaded as a side-effect of retransforming java.lang.Thread, so the
    // ClassFileTransformer is suppressed by the JVMTI re-entrancy rule.
    "java.lang.VirtualThread",
    // Loaded while the socket-channel group retransforms the channel classes (their matchers and
    // advice resolve the socket types), so they can escape the socket group's transformer.
    "java.net.Socket",
    "java.net.ServerSocket",
    "sun.nio.ch.NioSocketImpl",
  };

  /**
   * Re-runs the {@link java.lang.instrument.ClassFileTransformer} for classes that were loaded as a
   * side-effect during {@link AgentBuilder#installOn} and so escaped the original transformer pass.
   * See {@link #POST_INSTALL_RETRANSFORM_TARGETS} for the list and rationale.
   */
  private static void retransformPostInstallEscapees(final Instrumentation instrumentation) {
    for (final String name : POST_INSTALL_RETRANSFORM_TARGETS) {
      try {
        final Class<?> cls = Class.forName(name, false, null);
        if (instrumentation.isModifiableClass(cls)) {
          instrumentation.retransformClasses(cls);
        }
      } catch (final ClassNotFoundException notLoaded) {
        // Not loaded yet — the regular ClassFileTransformer will catch it on first use.
      } catch (final Throwable failure) {
        LOGGER.warning("failed to retransform " + name + ": " + failure);
      }
    }
  }

  /**
   * Applies {@code advice} to the methods {@code methods} matches, recording whether it matched any
   * ({@link HookCoverage}): a hook whose JDK method changed is reported instead of going inert.
   */
  private static AsmVisitorWrapper advise(
      final Class<?> advice, final ElementMatcher<? super MethodDescription> methods) {
    return Advice.to(advice).on(HookCoverage.track(advice, methods));
  }

  /**
   * Builds the base {@link AgentBuilder} with retransformation strategy, error logging, and ignore
   * rules for ByteBuddy and chaos-agent internal packages.
   */
  private static AgentBuilder buildBaseAgentBuilder() {
    final AgentBuilder.Listener.Adapter errorListener =
        new AgentBuilder.Listener.Adapter() {
          @Override
          public void onError(
              final String typeName,
              final ClassLoader classLoader,
              final JavaModule module,
              final boolean loaded,
              final Throwable throwable) {
            HookCoverage.failed(typeName, throwable);
            LOGGER.warning("chaos instrumentation failed for " + typeName + ": " + throwable);
          }
        };
    return new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(errorListener)
        .ignore(
            ElementMatchers.nameStartsWith("net.bytebuddy.")
                .or(ElementMatchers.nameStartsWith("com.macstab.chaos.jvm.")));
  }

  /**
   * Thread.start instrumentation.
   *
   * <p>Targets {@code java.lang.Thread.start()} for platform threads <i>and</i> {@code
   * java.lang.VirtualThread.start()} for virtual threads. Virtual threads override {@code
   * Thread.start()} (it is not {@code final}) and dispatch directly to {@code
   * VirtualThread.start(DEFAULT_SCHEDULER)} without calling {@code Thread.start()}, so a
   * Thread-only weave silently misses every {@code VIRTUAL_THREAD_START} event. The shared advice
   * captures {@code @Advice.This Thread} and the runtime dispatcher emits {@code THREAD_START} vs
   * {@code VIRTUAL_THREAD_START} based on {@code Thread.isVirtual()}.
   */
  private static AgentBuilder applyThreadStartTransformations(final AgentBuilder builder) {
    return builder
        // Worker creation: a thread started inside addWorker is a pool worker (see
        // BootstrapDispatcher.SCOPE_POOL_WORKER). Tomcat ships its own copy of the class.
        .type(
            ElementMatchers.namedOneOf(
                "java.util.concurrent.ThreadPoolExecutor",
                "org.apache.tomcat.util.threads.ThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.PoolWorkerScopeAdvice.class,
                        ElementMatchers.named("addWorker").and(ElementMatchers.takesArguments(2)))))
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.StartAdvice.class,
                        ElementMatchers.named("start")
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(
                                        ElementMatchers.takesArguments(1)
                                            .and(
                                                ElementMatchers.takesArgument(
                                                    0,
                                                    ElementMatchers.named(
                                                        "jdk.internal.vm.ThreadContainer"))))))))
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.RunSessionAdvice.class,
                        ElementMatchers.named("run").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.lang.VirtualThread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ThreadAdvice.RunSessionAdvice.class,
                            ElementMatchers.named("run")
                                .and(ElementMatchers.takesArguments(Runnable.class))))
                    .visit(
                        advise(
                            ThreadAdvice.MountSessionAdvice.class,
                            ElementMatchers.named("mount").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.lang.VirtualThread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ThreadAdvice.StartAdvice.class,
                        ElementMatchers.named("start")
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(
                                        ElementMatchers.takesArguments(1)
                                            .and(
                                                ElementMatchers.takesArgument(
                                                    0,
                                                    ElementMatchers.named(
                                                        "jdk.internal.vm.ThreadContainer"))))))));
  }

  /** ThreadPoolExecutor execute/beforeExecute/shutdown/shutdownNow/awaitTermination. */
  private static AgentBuilder applyThreadPoolExecutorTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ExecutorAdvice.ExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(ElementMatchers.takesArguments(Runnable.class))))
                    .visit(
                        advise(
                            ExecutorAdvice.BeforeExecuteAdvice.class,
                            ElementMatchers.named("beforeExecute")
                                .and(ElementMatchers.takesArguments(Thread.class, Runnable.class))))
                    .visit(
                        advise(
                            ExecutorAdvice.ShutdownAdvice.class,
                            ElementMatchers.named("shutdown")
                                .and(ElementMatchers.takesArguments(0))))
                    // shutdownNow() intercepts the forced-shutdown variant that interrupts
                    // in-flight tasks and returns the undrained queue. Omitting it left any
                    // selector targeting EXECUTOR_SHUTDOWN silently missing shutdown paths
                    // that went through shutdownNow instead of shutdown.
                    .visit(
                        advise(
                            ExecutorAdvice.ShutdownNowAdvice.class,
                            ElementMatchers.named("shutdownNow")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ExecutorAdvice.AwaitTerminationAdvice.class,
                            ElementMatchers.named("awaitTermination")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class, java.util.concurrent.TimeUnit.class)))));
  }

  /** ScheduledThreadPoolExecutor schedule / scheduleAtFixedRate / scheduleWithFixedDelay. */
  private static AgentBuilder applyScheduledExecutorTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.ScheduleRunnableAdvice.class,
                            ElementMatchers.named("schedule")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.ScheduleCallableAdvice.class,
                            ElementMatchers.named("schedule")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Callable.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.PeriodicAdvice.class,
                            ElementMatchers.named("scheduleAtFixedRate")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class))))
                    .visit(
                        advise(
                            ScheduledExecutorAdvice.PeriodicAdvice.class,
                            ElementMatchers.named("scheduleWithFixedDelay")
                                .and(
                                    ElementMatchers.takesArguments(
                                        Runnable.class,
                                        long.class,
                                        long.class,
                                        java.util.concurrent.TimeUnit.class)))));
  }

  /** BlockingQueue put/take/poll/offer instrumentation. */
  private static AgentBuilder applyBlockingQueueTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.isSubTypeOf(java.util.concurrent.BlockingQueue.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(advise(QueueAdvice.PutAdvice.class, ElementMatchers.named("put")))
                    .visit(advise(QueueAdvice.TakeAdvice.class, ElementMatchers.named("take")))
                    .visit(advise(QueueAdvice.PollAdvice.class, ElementMatchers.named("poll")))
                    .visit(
                        advise(
                            QueueAdvice.OfferAdvice.class,
                            ElementMatchers.named("offer")
                                .and(ElementMatchers.takesArguments(1)))));
  }

  /** CompletableFuture complete / completeExceptionally instrumentation. */
  private static AgentBuilder applyCompletableFutureTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            CompletableFutureAdvice.CompleteAdvice.class,
                            ElementMatchers.named("complete")
                                .and(ElementMatchers.takesArguments(1))))
                    .visit(
                        advise(
                            CompletableFutureAdvice.CompleteExceptionallyAdvice.class,
                            ElementMatchers.named("completeExceptionally")
                                .and(ElementMatchers.takesArguments(Throwable.class)))));
  }

  /** ClassLoader loadClass / getResource instrumentation. */
  private static AgentBuilder applyClassLoaderTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ClassLoader"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        // Must match BOTH 1-arg and 2-arg overloads: BuiltinClassLoader
                        // (the superclass of every default JDK classloader) overrides the
                        // 2-arg variant but inherits the 1-arg from java.lang.ClassLoader,
                        // so instrumenting only the 2-arg would miss every standard class
                        // load via Class.forName / loader.loadClass(name). Loaders that
                        // inherit both overloads (URLClassLoader) and parent delegation reach
                        // the advice more than once per load; the SCOPE_CLASS_LOAD call-depth
                        // scope in LoadClassAdvice evaluates only the outermost call.
                        advise(
                            ClassLoaderAdvice.LoadClassAdvice.class,
                            ElementMatchers.named("loadClass")
                                .and(
                                    ElementMatchers.takesArguments(String.class, boolean.class)
                                        .or(ElementMatchers.takesArguments(String.class)))))
                    .visit(
                        advise(
                            ClassLoaderAdvice.GetResourceAdvice.class,
                            ElementMatchers.named("getResource")
                                .and(ElementMatchers.takesArguments(String.class)))));
  }

  /** Runtime addShutdownHook / removeShutdownHook instrumentation. */
  private static AgentBuilder applyShutdownHookTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.Runtime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ShutdownAdvice.AddShutdownHookAdvice.class,
                            ElementMatchers.named("addShutdownHook")
                                .and(ElementMatchers.takesArguments(Thread.class))))
                    .visit(
                        advise(
                            ShutdownAdvice.RemoveShutdownHookAdvice.class,
                            ElementMatchers.named("removeShutdownHook")
                                .and(ElementMatchers.takesArguments(Thread.class)))));
  }

  /** ForkJoinTask.doExec instrumentation. */
  private static AgentBuilder applyForkJoinTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.ForkJoinTask"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            ForkJoinAdvice.DoExecAdvice.class,
                            ElementMatchers.named("doExec").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ForkJoinAdvice.ForkAdvice.class,
                            ElementMatchers.named("fork").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.util.concurrent.ForkJoinPool"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ForkJoinAdvice.PoolSubmitAdvice.class,
                        ElementMatchers.named("poolSubmit")
                            .and(ElementMatchers.takesArguments(2)))));
  }

  /** System.exit, System.gc, Runtime.gc, Runtime.halt instrumentation. */
  private static AgentBuilder applyRuntimeLifecycleTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.System"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                // System.gc() is a non-native Java method that delegates to Runtime.gc().
                // Runtime.gc() is native and cannot be instrumented; System.gc() can be.
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.GcRequestAdvice.class,
                        ElementMatchers.named("gc").and(ElementMatchers.takesArguments(0)))))
        // System.exit(int) delegates to Runtime.exit(int); hooking Runtime.exit covers both
        // entry points once. Runtime.halt does not go through Runtime.exit.
        .type(ElementMatchers.named("java.lang.Runtime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ExitRequestAdvice.class,
                        ElementMatchers.namedOneOf("exit", "halt")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /** {@code Method.invoke} interception (REFLECTION_INVOKE). */
  private static AgentBuilder applyReflectionInvokeTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.reflect.Method"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ReflectionInvokeAdvice.class,
                        ElementMatchers.named("invoke")
                            .and(ElementMatchers.takesArguments(Object.class, Object[].class)))));
  }

  /** {@code ByteBuffer.allocateDirect} interception (DIRECT_BUFFER_ALLOCATE). */
  private static AgentBuilder applyDirectBufferTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.nio.ByteBuffer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.DirectBufferAllocateAdvice.class,
                        ElementMatchers.named("allocateDirect")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /** {@code ObjectInputStream.readObject} / {@code ObjectOutputStream.writeObject} interception. */
  private static AgentBuilder applySerializationTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.io.ObjectInputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ObjectDeserializeAdvice.class,
                        ElementMatchers.named("readObject")
                            .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.io.ObjectOutputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ObjectSerializeAdvice.class,
                        ElementMatchers.named("writeObject")
                            .and(ElementMatchers.takesArguments(Object.class)))));
  }

  /** {@code ClassLoader.defineClass} interception (CLASS_DEFINE). */
  private static AgentBuilder applyClassDefineTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ClassLoader"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ClassDefineAdvice.class,
                        ElementMatchers.named("defineClass")
                            .and(ElementMatchers.takesArgument(0, String.class)))));
  }

  /**
   * LockSupport park / parkNanos / parkUntil (THREAD_PARK). Safe to instrument globally: the chaos
   * runtime never calls LockSupport.park() directly. Internal locks (ManualGate via ReentrantLock)
   * do call park(), but by the time park() fires, BootstrapDispatcher.DEPTH is already > 0 (set
   * before delegating to ChaosRuntime), so the DEPTH guard in invoke() returns the fallback
   * immediately without entering chaos evaluation.
   */
  private static AgentBuilder applyLockSupportTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.locks.LockSupport"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    // park(Object blocker) — used by j.u.c. locks that set the blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("park")
                                .and(ElementMatchers.takesArguments(Object.class))))
                    // park() — 0-arg variant used by direct LockSupport callers
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("park").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkNanos")
                                .and(ElementMatchers.takesArguments(Object.class, long.class))))
                    // parkNanos(long nanos) — 1-arg variant without blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkNanos")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkUntil")
                                .and(ElementMatchers.takesArguments(Object.class, long.class))))
                    // parkUntil(long deadline) — 1-arg variant without blocker
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadParkAdvice.class,
                            ElementMatchers.named("parkUntil")
                                .and(ElementMatchers.takesArguments(long.class)))));
  }

  /**
   * AQS.acquire (MONITOR_ENTER proxy). Safe: ChaosRuntime uses ConcurrentHashMap (lock-free) for
   * registry.match(). The one internal AQS user is ManualGate (ReentrantLock), but ManualGate is
   * only entered from applyGate() which runs inside DEPTH > 0 context — the DEPTH guard
   * short-circuits before chaos evaluation begins, preventing recursion.
   */
  private static AgentBuilder applyAqsMonitorTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.locks.AbstractQueuedSynchronizer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.MonitorEnterAdvice.class,
                        ElementMatchers.named("acquire")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /**
   * NIO Selector, SocketChannel, and ServerSocketChannel instrumentation. Concrete-subtype-only
   * matchers avoid VerifyError on bridge / synthetic methods of abstract bases.
   */
  private static AgentBuilder applyNioChannelTransformations(final AgentBuilder builder) {
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
                            JvmRuntimeAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(ElementMatchers.takesArguments(0))))
                    // JDK 11+ select(Consumer) / select(Consumer, long) / selectNow(Consumer)
                    // reach lockAndDoSelect without calling the overloads above.
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectConsumerTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class, long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.util.function.Consumer.class)))))
        .type(
            ElementMatchers.isSubTypeOf(java.nio.channels.spi.AbstractSelector.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectNoArgAdvice.class,
                            ElementMatchers.named("select").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectTimeoutAdvice.class,
                            ElementMatchers.named("select")
                                .and(ElementMatchers.takesArguments(long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioSelectNowAdvice.class,
                            ElementMatchers.named("selectNow")
                                .and(ElementMatchers.takesArguments(0)))))
        // NIO SocketChannel — the public class is abstract, so name() would only match the
        // abstract copy; instrument concrete subtypes (sun.nio.ch.SocketChannelImpl,
        // possibly vendor variants) where the actual I/O code lives. Exclude abstract /
        // synthetic matches to avoid VerifyError on bridge methods.
        .type(
            ElementMatchers.isSubTypeOf(java.nio.channels.SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioChannelConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(ElementMatchers.takesArguments(java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.NioChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class)))))
        // NIO ServerSocketChannel — same abstract-base issue as SocketChannel above.
        .type(
            ElementMatchers.isSubTypeOf(java.nio.channels.ServerSocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.NioChannelAcceptAdvice.class,
                        ElementMatchers.named("accept").and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * UDP: {@code DatagramChannelImpl}'s public sends and receives, and the funnels {@code
   * DatagramSocket} goes through ({@code blockingSend}, {@code blockingReceive}).
   */
  private static AgentBuilder applyDatagramTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("sun.nio.ch.DatagramChannelImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramSendAdvice.class,
                            ElementMatchers.named("send")
                                .and(ElementMatchers.isPublic())
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer.class, java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramPacketSendAdvice.class,
                            ElementMatchers.named("blockingSend")
                                .and(
                                    ElementMatchers.takesArguments(java.net.DatagramPacket.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramReceiveAdvice.class,
                            ElementMatchers.named("receive")
                                .and(ElementMatchers.isPublic())
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DatagramPacketReceiveAdvice.class,
                            ElementMatchers.named("blockingReceive")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.net.DatagramPacket.class, long.class)))));
  }

  /**
   * {@code SocketChannel} and {@code ServerSocketChannel} as sockets: connect, accept, reads and
   * writes (single, scattering, gathering) raise the {@code SOCKET_*} events with the channel's
   * peer and byte count, and a socket channel's close raises {@code SOCKET_CLOSE}. Matched on the
   * concrete subtypes, where the I/O lives; the close on {@code AbstractInterruptibleChannel}, the
   * only class declaring it, while the channel is still open.
   */
  private static AgentBuilder applySocketChannelTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.isSubTypeOf(java.nio.channels.SocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(ElementMatchers.takesArguments(java.net.SocketAddress.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelShutdownAdvice.class,
                            ElementMatchers.namedOneOf("shutdownInput", "shutdownOutput")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class)))))
        .type(
            ElementMatchers.isSubTypeOf(java.nio.channels.ServerSocketChannel.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.SocketChannelAcceptAdvice.class,
                        ElementMatchers.named("accept").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.nio.channels.spi.AbstractInterruptibleChannel"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.SocketChannelCloseAdvice.class,
                        ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * Socket / ServerSocket / SocketInputStream / SocketOutputStream instrumentation. Only
   * instruments the 3-argument bulk variant of SocketInputStream.read(): the 0-arg single-byte
   * overload delegates internally to read(byte[], int, int), causing double-fire of
   * beforeSocketRead for each single-byte application call. Restricting to the final I/O dispatch
   * avoids double-counting rate-limit permits and probability draws.
   */
  private static AgentBuilder applySocketAndStreamTransformations(final AgentBuilder builder) {
    return builder
        // Every bind of a socket or socket channel, explicit or implicit, funnels into this one.
        .type(ElementMatchers.named("sun.nio.ch.Net"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.SocketBindAdvice.class,
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
                            JvmRuntimeAdvice.SocketConnectAdvice.class,
                            ElementMatchers.named("connect")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.net.SocketAddress.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketCloseAdvice.class,
                            ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.net.ServerSocket"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.SocketAcceptAdvice.class,
                        ElementMatchers.named("accept").and(ElementMatchers.takesArguments(0)))))
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
                            JvmRuntimeAdvice.SocketShutdownAdvice.class,
                            ElementMatchers.namedOneOf("shutdownInput", "shutdownOutput")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    // NioSocketImpl declares no write(int): single-byte writes of its output
                    // stream go through write(byte[], int, int) with len 1.
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SocketWriteBulkAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))));
  }

  /** ZIP Inflater / Deflater instrumentation. */
  private static AgentBuilder applyZipTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.zip.Inflater"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ZipInflateAdvice.class, ElementMatchers.named("inflate"))))
        .type(ElementMatchers.named("java.util.zip.Deflater"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ZipDeflateAdvice.class,
                        ElementMatchers.named("deflate"))));
  }

  /**
   * ThreadLocal.get() / set() (THREAD_LOCAL_GET / THREAD_LOCAL_SET). Previously excluded due to
   * reentrancy with BootstrapDispatcher.DEPTH (also a ThreadLocal). Now safe: ThreadLocalGetAdvice
   * and ThreadLocalSetAdvice include an identity check (threadLocal == BootstrapDispatcher.DEPTH)
   * that exits before any delegation, breaking the recursion at the only point where it could
   * occur. See ThreadLocalGetAdvice for the full reentrancy analysis.
   */
  private static AgentBuilder applyThreadLocalTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ThreadLocal"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadLocalGetAdvice.class,
                            ElementMatchers.named("get").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadLocalSetAdvice.class,
                            ElementMatchers.named("set").and(ElementMatchers.takesArguments(1)))));
  }

  /**
   * Higher-level time APIs. Direct System.currentTimeMillis() / System.nanoTime() cannot be
   * intercepted (see note in install()) but java.time.Instant.now(), java.time.LocalDateTime.now(),
   * java.time.ZonedDateTime.now(), and java.util.Date() are regular Java members that can be woven
   * at exit.
   */
  private static AgentBuilder applyTimeApiTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.time.Instant"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.InstantNowAdvice.class,
                        ElementMatchers.named("now")
                            .and(ElementMatchers.isStatic())
                            .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.time.LocalDateTime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.LocalDateTimeNowAdvice.class,
                            ElementMatchers.named("now")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(0))))
                    // now(ZoneId) goes to now(Clock) without calling now(); now(Clock) is not
                    // hooked because a caller-supplied clock (fixed, offset) must not be skewed.
                    .visit(
                        advise(
                            JvmRuntimeAdvice.LocalDateTimeNowInZoneAdvice.class,
                            ElementMatchers.named("now")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(java.time.ZoneId.class)))))
        .type(ElementMatchers.named("java.time.ZonedDateTime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                // The returned ZonedDateTime carries its zone, so one advice covers now() and
                // now(ZoneId).
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ZonedDateTimeNowAdvice.class,
                        ElementMatchers.named("now")
                            .and(ElementMatchers.isStatic())
                            .and(
                                ElementMatchers.takesArguments(0)
                                    .or(ElementMatchers.takesArguments(java.time.ZoneId.class))))))
        // The system clock and instant source: every other java.time now() reads one of them.
        .type(
            ElementMatchers.namedOneOf(
                "java.time.Clock$SystemClock", "java.time.Clock$SystemInstantSource"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SystemClockInstantAdvice.class,
                            ElementMatchers.named("instant")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.SystemClockMillisAdvice.class,
                            ElementMatchers.named("millis")
                                .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.util.Date"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.DateNewAdvice.class,
                        ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(0)))));
  }

  /** CompletableFuture.cancel instrumentation. */
  private static AgentBuilder applyCompletableFutureCancelTransformations(
      final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.AsyncCancelAdvice.class,
                        ElementMatchers.named("cancel")
                            .and(ElementMatchers.takesArguments(boolean.class)))));
  }

  /**
   * SSL/TLS handshake interception on concrete SSLSocket / SSLEngine subtypes. SSLSocket and
   * SSLEngine themselves are abstract; concrete subclasses (SSLSocketImpl, SSLEngineImpl) override
   * startHandshake / beginHandshake, so advice on the abstract class alone would never fire.
   * Interface / synthetic filters exclude Mockito/CGLIB generated proxies that would otherwise trip
   * VerifyError during class transformation.
   */
  private static AgentBuilder applySslHandshakeTransformations(final AgentBuilder builder) {
    AgentBuilder agentBuilder =
        builder
            .type(
                ElementMatchers.isSubTypeOf(javax.net.ssl.SSLSocket.class)
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
                            JvmRuntimeAdvice.SslHandshakeAdvice.class,
                            ElementMatchers.named("startHandshake")
                                .and(
                                    ElementMatchers.takesArguments(0)
                                        .or(ElementMatchers.takesArguments(boolean.class))))));

    agentBuilder =
        agentBuilder
            .type(
                ElementMatchers.isSubTypeOf(javax.net.ssl.SSLEngine.class)
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
            .transform(
                (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                    typeBuilder.visit(
                        advise(
                            JvmRuntimeAdvice.SslHandshakeAdvice.class,
                            ElementMatchers.named("beginHandshake")
                                .and(ElementMatchers.takesArguments(0)))));

    return agentBuilder;
  }

  /**
   * Registers HTTP client interception: Java {@code java.net.http.HttpClient} (JDK 11+), OkHttp,
   * Apache HC 4.x, Apache HC 5.x, Reactor Netty.
   *
   * <p>All library targets use {@link #instrumentOptional}, which matches them by name whenever
   * they are loaded, by any class loader. Advice classes only use Object-typed arguments and
   * reflective URL extraction, so the compileOnly dependencies (OkHttp, Apache HC, Reactor Netty)
   * are not required at runtime.
   *
   * <p>Java 11+ {@code HttpClient} is implemented by {@code jdk.internal.net.http.HttpClientImpl},
   * which lives in the non-exported {@code java.net.http/jdk.internal.net.http} package. Two pieces
   * are required to instrument it without users having to set {@code --add-opens} JVM flags:
   *
   * <ol>
   *   <li>Module access — granted automatically by {@link #grantInternalModuleOpens} (programmatic
   *       {@code redefineModule}) plus the {@code Add-Opens} attribute in the agent JAR's {@code
   *       MANIFEST.MF}.
   *   <li>A separate {@link AgentBuilder} pass — historically, mixing this transformation into the
   *       same builder pass as the other Phase 2 transformations could cause silent corruption of
   *       the retransformation pipeline. With the open already in place at install time the
   *       transformation registers cleanly inside this method.
   * </ol>
   */
  private static AgentBuilder applyPhase2HttpClientInterception(AgentBuilder agentBuilder) {
    // Java 11+ HttpClient — concrete impl in jdk.internal.net.http. Module access is self-granted
    // by grantInternalModuleOpens() / Add-Opens manifest entry, no user JVM flags required.
    // sendAsync(request, handler) delegates to the public sendAsync(request, handler,
    // pushPromiseHandler); both are hooked and SCOPE_HTTP evaluates one call once.
    agentBuilder =
        agentBuilder
            .type(ElementMatchers.named("jdk.internal.net.http.HttpClientImpl"))
            .transform(
                (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                    typeBuilder
                        .visit(
                            advise(
                                HttpClientAdvice.JavaHttpClientSendAdvice.class,
                                ElementMatchers.named("send")
                                    .and(ElementMatchers.takesArguments(2))))
                        .visit(
                            advise(
                                HttpClientAdvice.JavaHttpClientSendAsyncAdvice.class,
                                ElementMatchers.named("sendAsync")
                                    .and(
                                        ElementMatchers.takesArguments(2)
                                            .or(ElementMatchers.takesArguments(3))))));

    agentBuilder =
        instrumentOptional(
            agentBuilder,
            typeBuilder ->
                typeBuilder
                    .visit(
                        advise(
                            HttpClientAdvice.OkHttpExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            HttpClientAdvice.OkHttpEnqueueAdvice.class,
                            ElementMatchers.named("enqueue")
                                .and(ElementMatchers.takesArguments(1)))),
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall");

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
            typeBuilder ->
                typeBuilder
                    .visit(
                        advise(
                            HttpClientAdvice.ApacheHc4ExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0, ElementMatchers.named("org.apache.http.HttpHost")))))
                    .visit(
                        advise(
                            HttpClientAdvice.ApacheHc4UriExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0,
                                        ElementMatchers.named(
                                            "org.apache.http.client.methods.HttpUriRequest"))))),
            "org.apache.http.impl.client.CloseableHttpClient");

    agentBuilder =
        instrumentOptional(
            agentBuilder,
            typeBuilder ->
                // HC5 execute overloads come in two shapes, distinguished by the first argument
                // (as for HC4): request-first execute(ClassicHttpRequest [, HttpContext]
                // [, handler]) and host-first execute(HttpHost, ClassicHttpRequest [, ...]). The
                // common one-argument execute(request) goes straight to doExecute, so matching
                // by arity missed it, and the host-first overloads handed an HttpHost to advice
                // expecting a request. Overloads delegate to each other; SCOPE_HTTP evaluates one
                // call once.
                typeBuilder
                    .visit(
                        advise(
                            HttpClientAdvice.ApacheHc5ExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0,
                                        ElementMatchers.named(
                                            "org.apache.hc.core5.http.ClassicHttpRequest")))))
                    .visit(
                        advise(
                            HttpClientAdvice.ApacheHc5HostExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0,
                                        ElementMatchers.named(
                                            "org.apache.hc.core5.http.HttpHost"))))),
            "org.apache.hc.client5.http.impl.classic.CloseableHttpClient");

    agentBuilder =
        agentBuilder
            .type(ElementMatchers.named("sun.net.www.protocol.http.HttpURLConnection"))
            .transform(
                (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                    typeBuilder
                        .visit(
                            advise(
                                HttpClientAdvice.HttpUrlConnectionConnectAdvice.class,
                                ElementMatchers.named("plainConnect0")
                                    .and(ElementMatchers.takesArguments(0))))
                        .visit(
                            advise(
                                HttpClientAdvice.HttpUrlConnectionSessionHeaderAdvice.class,
                                ElementMatchers.namedOneOf(
                                        "connect", "getInputStream", "getOutputStream")
                                    .and(ElementMatchers.takesArguments(0)))));
    return instrumentOptional(
        agentBuilder,
        typeBuilder ->
            typeBuilder.visit(
                // HttpClientConnect.connect() takes no arguments; the request URI comes from the
                // client configuration. Matching named("connect") alone also hit the generated
                // lambda$connect$N methods.
                advise(
                    HttpClientAdvice.ReactorNettyConnectAdvice.class,
                    ElementMatchers.named("connect").and(ElementMatchers.takesArguments(0)))),
        "reactor.netty.http.client.HttpClientConnect");
  }

  /**
   * Registers JDBC and connection-pool interception: HikariCP, c3p0, and concrete {@link
   * java.sql.Statement} / {@link java.sql.Connection} subtypes.
   *
   * <p>HikariCP and c3p0 are compileOnly dependencies and are instrumented via {@link
   * #instrumentOptional} so their absence is tolerated. {@code java.sql.Statement} and {@code
   * java.sql.Connection} are JDK interfaces — the type matcher is restricted to concrete subtypes
   * via {@code isSubTypeOf + not(isInterface())} so the advice only binds to implementations
   * (driver classes such as {@code HikariProxyStatement}, {@code org.postgresql.jdbc.PgStatement},
   * etc.).
   */
  private static AgentBuilder applyPhase2JdbcInterception(AgentBuilder agentBuilder) {
    agentBuilder =
        instrumentOptional(
            agentBuilder,
            typeBuilder ->
                typeBuilder.visit(
                    advise(
                        JdbcAdvice.HikariGetConnectionAdvice.class,
                        ElementMatchers.named("getConnection")
                            .and(ElementMatchers.takesArguments(long.class)))),
            "com.zaxxer.hikari.pool.HikariPool");

    agentBuilder =
        instrumentOptional(
            agentBuilder,
            typeBuilder ->
                typeBuilder.visit(
                    advise(
                        JdbcAdvice.C3p0CheckoutAdvice.class,
                        ElementMatchers.named("checkoutPooledConnection")
                            .and(ElementMatchers.takesArguments(0)))),
            "com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool");

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
    return agentBuilder
        .type(
            ElementMatchers.isSubTypeOf(java.sql.Statement.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JdbcAdvice.StatementExecuteAdvice.class,
                            ElementMatchers.named("execute")
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    .visit(
                        advise(
                            JdbcAdvice.StatementExecuteAdvice.class,
                            ElementMatchers.named("executeQuery")
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    .visit(
                        advise(
                            JdbcAdvice.StatementExecuteAdvice.class,
                            ElementMatchers.named("executeUpdate")
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    .visit(
                        advise(
                            JdbcAdvice.StatementExecuteAdvice.class,
                            ElementMatchers.named("executeLargeUpdate")
                                .and(ElementMatchers.takesArgument(0, String.class)))))
        .type(
            ElementMatchers.isSubTypeOf(java.sql.PreparedStatement.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JdbcAdvice.PreparedStatementExecuteAdvice.class,
                        ElementMatchers.namedOneOf(
                                "execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                            .and(ElementMatchers.takesArguments(0))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        .type(
            ElementMatchers.isSubTypeOf(java.sql.Connection.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JdbcAdvice.PrepareStatementAdvice.class,
                            ElementMatchers.named("prepareStatement")
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    .visit(
                        advise(
                            JdbcAdvice.PrepareStatementAdvice.class,
                            ElementMatchers.named("prepareCall")
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    .visit(
                        advise(
                            JdbcAdvice.CommitAdvice.class,
                            ElementMatchers.named("commit").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JdbcAdvice.RollbackAdvice.class,
                            ElementMatchers.named("rollback")
                                .and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * Registers JNDI ({@code javax.naming.InitialContext}) and JMX ({@code
   * javax.management.MBeanServer}) interception.
   */
  private static AgentBuilder applyPhase2JndiAndJmxInterception(AgentBuilder agentBuilder) {
    agentBuilder =
        instrumentOptional(
            agentBuilder,
            typeBuilder ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.JndiLookupAdvice.class,
                        ElementMatchers.named("lookup")
                            .and(ElementMatchers.takesArguments(String.class)))),
            "javax.naming.InitialContext");

    // MBeanServer is an interface — ByteBuddy cannot inline advice into abstract interface
    // methods. Target concrete subtypes (e.g. JmxMBeanServer) instead.
    return agentBuilder
        .type(
            ElementMatchers.isSubTypeOf(javax.management.MBeanServer.class)
                .and(ElementMatchers.not(ElementMatchers.isInterface()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.JmxInvokeAdvice.class,
                            ElementMatchers.named("invoke").and(ElementMatchers.takesArguments(4))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.JmxGetAttrAdvice.class,
                            ElementMatchers.named("getAttribute")
                                .and(ElementMatchers.takesArguments(2)))));
  }

  /**
   * Registers native-library-load interception.
   *
   * <p>{@code System.load(path)} and {@code System.loadLibrary(name)} both delegate to non-native
   * {@code Runtime.load0(Class, String)} / {@code Runtime.loadLibrary0(Class, String)} — these are
   * the stable interception points across modern JDKs. {@code
   * jdk.internal.loader.NativeLibraries.load} cannot be woven because on JDK 17+ it is declared
   * {@code native} (the actual library load is performed by JNI), and bytecode advice cannot be
   * inlined into a native method. Missing sites are tolerated by {@link #instrumentOptional}.
   */
  private static AgentBuilder applyPhase2NativeLibraryLoadInterception(AgentBuilder agentBuilder) {
    return instrumentOptional(
        agentBuilder,
        typeBuilder ->
            typeBuilder
                .visit(
                    advise(
                        JvmRuntimeAdvice.NativeLibraryLoadAdvice.class,
                        ElementMatchers.named("loadLibrary0")))
                .visit(
                    advise(
                        JvmRuntimeAdvice.NativeLibraryLoadAdvice.class,
                        ElementMatchers.named("load0"))),
        "java.lang.Runtime");
  }

  /** Registers {@link Thread#sleep(long)} and {@link Thread#sleep(long, int)} interception. */
  private static AgentBuilder applyPhase2ThreadSleepInterception(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(ElementMatchers.named("java.lang.Thread"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    // sleep(long millis)
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadSleepAdvice.class,
                            ElementMatchers.named("sleep")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(long.class))))
                    // sleep(long millis, int nanos) — advice reads only arg 0 (millis)
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadSleepAdvice.class,
                            ElementMatchers.named("sleep")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(long.class, int.class))))
                    // sleep(Duration) — converts to nanos itself instead of calling sleep(long)
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ThreadSleepDurationAdvice.class,
                            ElementMatchers.named("sleep")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(java.time.Duration.class)))));
  }

  /**
   * Registers DNS resolution interception ({@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, {@link java.net.InetAddress#getLocalHost()}).
   *
   * <p>InetAddress is always present; no {@link #instrumentOptional} needed.
   */
  private static AgentBuilder applyPhase2DnsResolutionInterception(
      final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(ElementMatchers.named("java.net.InetAddress"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DnsResolveAdvice.class,
                            ElementMatchers.named("getByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DnsResolveAdvice.class,
                            ElementMatchers.named("getAllByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DnsAnswerAdvice.class,
                            ElementMatchers.named("getAllByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DnsLocalHostAdvice.class,
                            ElementMatchers.named("getLocalHost")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(0))))
                    // getHostName and getCanonicalHostName both look the name up here.
                    .visit(
                        advise(
                            JvmRuntimeAdvice.DnsReverseAdvice.class,
                            // (InetAddress, boolean) up to JDK 24, (InetAddress) since
                            ElementMatchers.named("getHostFromNameService")
                                .and(ElementMatchers.isStatic())
                                .and(
                                    ElementMatchers.takesArgument(
                                        0, java.net.InetAddress.class)))));
  }

  /**
   * Registers file I/O interception ({@link java.io.FileInputStream#read()} overloads and {@link
   * java.io.FileOutputStream#write(int)} overloads).
   */
  private static AgentBuilder applyPhase2FileIoInterception(final AgentBuilder agentBuilder) {
    return applyFileChannelInterception(agentBuilder)
        .type(ElementMatchers.named("java.io.FileInputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileReadByteAdvice.class,
                            ElementMatchers.named("read").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileReadArrayAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(byte[].class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileReadRangeAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))))
        .type(ElementMatchers.named("java.io.FileOutputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileWriteByteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileWriteArrayAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(byte[].class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileWriteRangeAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))));
  }

  /** Kafka producer send and consumer poll. */
  private static AgentBuilder applyKafkaTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("org.apache.kafka.clients.producer.KafkaProducer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.KafkaSend.class,
                        ElementMatchers.named("send").and(ElementMatchers.takesArguments(2)))))
        .type(ElementMatchers.named("org.apache.kafka.clients.consumer.KafkaConsumer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.KafkaPoll.class,
                        ElementMatchers.named("poll")
                            .and(ElementMatchers.takesArguments(java.time.Duration.class)))));
  }

  /** gRPC client call start, once per call for every stub flavour and transport. */
  private static AgentBuilder applyGrpcTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("io.grpc.internal.ClientCallImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.GrpcStart.class,
                        ElementMatchers.named("start").and(ElementMatchers.takesArguments(2)))));
  }

  /** Jedis and Lettuce command dispatch. */
  private static AgentBuilder applyRedisTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("redis.clients.jedis.Connection"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.JedisSendCommand.class,
                        ElementMatchers.named("sendCommand")
                            .and(
                                ElementMatchers.takesArguments(1)
                                    .and(
                                        ElementMatchers.takesArgument(
                                            0,
                                            ElementMatchers.named(
                                                "redis.clients.jedis.CommandArguments")))))))
        .type(ElementMatchers.named("io.lettuce.core.RedisChannelHandler"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.LettuceDispatch.class,
                        ElementMatchers.named("dispatch")
                            .and(
                                ElementMatchers.takesArgument(
                                    0,
                                    ElementMatchers.named(
                                        "io.lettuce.core.protocol.RedisCommand"))))));
  }

  /** R2DBC {@code Statement.execute()} in any driver. */
  private static AgentBuilder applyR2dbcTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.hasSuperType(ElementMatchers.named("io.r2dbc.spi.Statement"))
                .and(ElementMatchers.not(ElementMatchers.isInterface())))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.R2dbcExecute.class,
                        ElementMatchers.named("execute")
                            .and(ElementMatchers.takesArguments(0))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                            .and(ElementMatchers.not(ElementMatchers.isBridge())))));
  }

  /** AWS SDK v2 sync and async client handlers. */
  private static AgentBuilder applyAwsSdkTransformations(final AgentBuilder builder) {
    return builder
        .type(
            ElementMatchers.namedOneOf(
                "software.amazon.awssdk.core.internal.handler.BaseSyncClientHandler",
                "software.amazon.awssdk.core.internal.handler.BaseAsyncClientHandler"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.AwsExecute.class,
                        ElementMatchers.named("execute")
                            .and(ElementMatchers.isPublic())
                            .and(
                                ElementMatchers.takesArgument(
                                    0,
                                    ElementMatchers.named(
                                        "software.amazon.awssdk.core.client.handler"
                                            + ".ClientExecutionParams"))))));
  }

  /** Netty channel flush (every transport) and stream-channel reads (NIO, epoll, kqueue). */
  private static AgentBuilder applyNettyTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("io.netty.channel.AbstractChannel$AbstractUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyFlush.class,
                        ElementMatchers.named("flush0").and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("io.netty.channel.nio.AbstractNioByteChannel$NioByteUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyRead.class,
                        ElementMatchers.named("read").and(ElementMatchers.takesArguments(0)))))
        .type(
            ElementMatchers.named(
                "io.netty.channel.epoll.AbstractEpollStreamChannel$EpollStreamUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        ClientCallAdvice.NettyRead.class, ElementMatchers.named("epollInReady"))))
        .type(
            ElementMatchers.named(
                "io.netty.channel.kqueue.AbstractKQueueStreamChannel$KQueueStreamUnsafe"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(ClientCallAdvice.NettyRead.class, ElementMatchers.named("readReady"))));
  }

  /**
   * File channels, {@code RandomAccessFile} and fsync. Only the private funnels are hooked ({@code
   * implRead}/{@code implWrite}, {@code readBytes}/{@code writeBytes}), so a public overload
   * delegating to them is one call.
   */
  private static AgentBuilder applyFileChannelInterception(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(ElementMatchers.named("sun.nio.ch.FileChannelImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArgument(0, java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArgument(0, java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelMapAdvice.class,
                            ElementMatchers.named("map")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0, java.nio.channels.FileChannel.MapMode.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelTransferToAdvice.class,
                            ElementMatchers.named("transferTo")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class,
                                        long.class,
                                        java.nio.channels.WritableByteChannel.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelTransferFromAdvice.class,
                            ElementMatchers.named("transferFrom")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.channels.ReadableByteChannel.class,
                                        long.class,
                                        long.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelCloseAdvice.class,
                            ElementMatchers.named("implCloseChannel")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileChannelForceAdvice.class,
                            ElementMatchers.named("force")
                                .and(ElementMatchers.takesArguments(boolean.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileTruncateAdvice.class,
                            ElementMatchers.named("truncate")
                                .and(ElementMatchers.takesArguments(long.class)))))
        .type(ElementMatchers.named("java.io.RandomAccessFile"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.RandomAccessReadByteAdvice.class,
                            ElementMatchers.named("read").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.RandomAccessReadRangeAdvice.class,
                            ElementMatchers.named("readBytes")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileWriteByteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileWriteRangeAdvice.class,
                            ElementMatchers.named("writeBytes")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileTruncateAdvice.class,
                            ElementMatchers.named("setLength")
                                .and(ElementMatchers.takesArguments(long.class)))))
        // Files.delete / deleteIfExists: the platform providers inherit both from
        // AbstractFileSystemProvider, which funnels them to implDelete.
        .type(ElementMatchers.named("sun.nio.fs.AbstractFileSystemProvider"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FilesDeleteAdvice.class,
                            ElementMatchers.named("delete")
                                .and(ElementMatchers.takesArguments(java.nio.file.Path.class))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FilesDeleteIfExistsAdvice.class,
                            ElementMatchers.named("deleteIfExists")
                                .and(ElementMatchers.takesArguments(java.nio.file.Path.class)))))
        // Opens and closes of java.io: the private open funnels of the constructors, close().
        .type(
            ElementMatchers.namedOneOf(
                "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.RandomAccessFile"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileOpenAdvice.class,
                            ElementMatchers.named("open")
                                .and(ElementMatchers.takesArgument(0, String.class))
                                .and(ElementMatchers.not(ElementMatchers.isNative()))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileStreamCloseAdvice.class,
                            ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))))
        // Opens of java.nio: Files.newInputStream / newOutputStream / newByteChannel and
        // FileChannel.open reach the platform provider's newByteChannel or newFileChannel.
        .type(
            ElementMatchers.nameStartsWith("sun.nio.fs.")
                .and(ElementMatchers.isSubTypeOf(java.nio.file.spi.FileSystemProvider.class)))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.FilesOpenAdvice.class,
                        ElementMatchers.namedOneOf("newByteChannel", "newFileChannel")
                            .and(ElementMatchers.takesArguments(3))
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        // Files.move: each platform provider implements move itself (UnixFileSystemProvider,
        // WindowsFileSystemProvider); other providers (zip file systems) are not hooked.
        .type(
            ElementMatchers.nameStartsWith("sun.nio.fs.")
                .and(ElementMatchers.isSubTypeOf(java.nio.file.spi.FileSystemProvider.class)))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FilesMoveAdvice.class,
                            ElementMatchers.named("move")
                                .and(ElementMatchers.takesArguments(3))
                                .and(ElementMatchers.not(ElementMatchers.isAbstract()))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FilesCopyAdvice.class,
                            ElementMatchers.named("copy")
                                .and(ElementMatchers.takesArguments(3))
                                .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))
                                .and(ElementMatchers.takesArgument(1, java.nio.file.Path.class))
                                .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        .type(ElementMatchers.named("java.io.File"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileDeleteAdvice.class,
                            ElementMatchers.named("delete").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.FileRenameAdvice.class,
                            ElementMatchers.named("renameTo")
                                .and(ElementMatchers.takesArguments(java.io.File.class)))))
        // msync of a mapped file region.
        .type(ElementMatchers.named("java.nio.MappedByteBuffer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.MappedBufferForceAdvice.class,
                        ElementMatchers.named("force")
                            .and(ElementMatchers.takesArguments(int.class, int.class)))))
        .type(ElementMatchers.named("java.io.FileDescriptor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.FileDescriptorSyncAdvice.class,
                        ElementMatchers.named("sync").and(ElementMatchers.takesArguments(0)))));
  }

  /**
   * Process starts: {@code ProcessBuilder.start(Redirect[])}, the private funnel of {@code
   * start()}, {@code startPipeline} and {@code Runtime.exec}.
   */
  private static AgentBuilder applyProcessInterception(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(ElementMatchers.named("java.lang.ProcessBuilder"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ProcessStartAdvice.class,
                        // By arity, not by parameter type: resolving ProcessBuilder$Redirect
                        // while ProcessBuilder is being transformed is a ClassCircularityError.
                        // start() has no other one-argument overload.
                        ElementMatchers.named("start").and(ElementMatchers.takesArguments(1)))))
        .type(ElementMatchers.named("java.lang.ProcessImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ProcessWaitAdvice.class,
                            ElementMatchers.named("waitFor")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.ProcessTimedWaitAdvice.class,
                            ElementMatchers.named("waitFor")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class, java.util.concurrent.TimeUnit.class)))));
  }

  /** Host globs that receive the session header besides internal hosts; set before injection. */
  private static volatile List<String> SESSION_HEADER_HOSTS = List.of();

  /** Prefixes whose clock call sites are rewritten; empty when the feature is off. */
  private static volatile List<String> CLOCK_CALL_SITE_PACKAGES = List.of();

  private static final List<String> JDK_PREFIXES =
      List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

  static List<String> validatedClockPackages(final List<String> packages) {
    final List<String> cleaned = new java.util.ArrayList<>();
    for (final String raw : packages) {
      final String prefix = raw == null ? "" : raw.strip();
      if (prefix.isEmpty()) {
        continue;
      }
      for (final String jdk : JDK_PREFIXES) {
        if (prefix.startsWith(jdk) || jdk.startsWith(prefix)) {
          throw new IllegalArgumentException("clockCallSites must not name JDK classes: " + prefix);
        }
      }
      cleaned.add(prefix);
    }
    return List.copyOf(cleaned);
  }

  /**
   * Returns the prefixes whose clock call sites are rewritten.
   *
   * @return the prefixes; empty when clock call-site weaving is off
   */
  static List<String> clockCallSitePackages() {
    return CLOCK_CALL_SITE_PACKAGES;
  }

  /**
   * Clock call sites: in classes under the configured prefixes, every {@code invokestatic} of
   * {@link System#currentTimeMillis()} and {@link System#nanoTime()} becomes a call of {@link
   * BootstrapDispatcher#currentTimeMillis()} / {@link BootstrapDispatcher#nanoTime()}, which return
   * the real value through the clock-skew dispatch. Loaded classes are retransformed; method bodies
   * change, nothing else.
   */
  private static AgentBuilder applyClockCallSiteTransformations(final AgentBuilder agentBuilder) {
    final List<String> packages = CLOCK_CALL_SITE_PACKAGES;
    if (packages.isEmpty()) {
      // A SYSTEM_CLOCK_* scenario is valid without the setting (manual adjustClockMillis hooks),
      // but direct System.currentTimeMillis() calls stay unskewed: say so instead of doing
      // nothing silently.
      LOGGER.warning(
          "chaos: a SYSTEM_CLOCK_MILLIS/NANOS scenario is active but clockCallSites is not set;"
              + " direct System.currentTimeMillis()/nanoTime() calls are not skewed");
      return agentBuilder;
    }
    ElementMatcher.Junction<TypeDescription> types = ElementMatchers.none();
    for (final String prefix : packages) {
      types = types.or(ElementMatchers.nameStartsWith(prefix));
    }
    final TypeDescription dispatcher = TypeDescription.ForLoadedType.of(BootstrapDispatcher.class);
    final net.bytebuddy.description.method.MethodDescription skewedMillis =
        dispatcher
            .getDeclaredMethods()
            .filter(ElementMatchers.named("currentTimeMillis"))
            .getOnly();
    final net.bytebuddy.description.method.MethodDescription skewedNanos =
        dispatcher.getDeclaredMethods().filter(ElementMatchers.named("nanoTime")).getOnly();
    return agentBuilder
        // Interfaces too: their default and static methods and the lambdas declared in them hold
        // clock calls as well.
        .type(types)
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    net.bytebuddy.asm.MemberSubstitution.relaxed()
                        .method(
                            ElementMatchers.named("currentTimeMillis")
                                .and(ElementMatchers.isDeclaredBy(System.class))
                                .and(ElementMatchers.takesArguments(0)))
                        .replaceWith(skewedMillis)
                        .method(
                            ElementMatchers.named("nanoTime")
                                .and(ElementMatchers.isDeclaredBy(System.class))
                                .and(ElementMatchers.takesArguments(0)))
                        .replaceWith(skewedNanos)
                        .on(ElementMatchers.any())));
  }

  /** Bridge jars older than this are left over from earlier JVMs and are removed. */
  private static final java.time.Duration STALE_BRIDGE_JAR_AGE = java.time.Duration.ofDays(1);

  /**
   * Removes bridge jars of earlier JVMs that could not delete their own (Windows, where an open
   * file cannot be deleted, or JVMs killed before exit). Only files with the bridge prefix that are
   * older than a day are considered; a jar still in use by a running JVM is locked on Windows and
   * the delete simply fails. Failures are ignored: the sweep is housekeeping only.
   */
  private static void sweepStaleBridgeJars() {
    final Path tmpPath = Path.of(System.getProperty("java.io.tmpdir"));
    final java.time.Instant cutoff = java.time.Instant.now().minus(STALE_BRIDGE_JAR_AGE);
    try (java.nio.file.DirectoryStream<Path> candidates =
        Files.newDirectoryStream(tmpPath, BRIDGE_JAR_PREFIX + "*" + BRIDGE_JAR_SUFFIX)) {
      for (final Path candidate : candidates) {
        try {
          if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              && Files.getLastModifiedTime(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                  .toInstant()
                  .isBefore(cutoff)) {
            Files.deleteIfExists(candidate);
          }
        } catch (final IOException | SecurityException skipped) {
          // In use, owned by another user, or already gone.
        }
      }
    } catch (final IOException | SecurityException unavailable) {
      LOGGER.fine(() -> "bridge jar sweep skipped: " + unavailable);
    }
  }

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

  /**
   * The class files of the bootstrap bridge jar. JDK classes woven by the agent resolve the types
   * their advice references through the bootstrap class loader, so every agent type the bridge
   * references must be in this list; {@code ArchitectureTest} checks that it is closed.
   */
  static final java.util.List<String> BRIDGE_CLASSES =
      java.util.List.of(
          "com/macstab/chaos/jvm/instrumentation/bridge/BootstrapDispatcher.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/SessionHeaderPolicy.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/ChannelBuffers.class",
          "com/macstab/chaos/jvm/instrumentation/bridge/OperationTags.class",
          "com/macstab/chaos/jvm/instrumentation/ScheduledRunnableWrapper.class",
          "com/macstab/chaos/jvm/instrumentation/ScheduledCallableWrapper.class",
          // Advice woven into JDK module classes (e.g. jdk.internal.net.http.HttpClientImpl in
          // the platform class loader) references these types; without them the verifier raises
          // NoClassDefFoundError when the JDK class is first used.
          "com/macstab/chaos/jvm/api/ChaosHttpSuppressException.class",
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/HttpUrlExtractor$1.class",
          // JDBC advice woven into HikariPool, c3p0 and java.sql types references these: the
          // inlined references resolve through the bootstrap class path.
          "com/macstab/chaos/jvm/api/ChaosJdbcSuppressException.class",
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor.class",
          "com/macstab/chaos/jvm/instrumentation/JdbcTargetExtractor$1.class");

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

  /**
   * Whether {@code delegate} was loaded by this installer's class loader (identity, not equals).
   */
  @SuppressWarnings("ReferenceEquality")
  private static boolean ownLoader(final Object delegate) {
    return delegate.getClass().getClassLoader()
        == JdkInstrumentationInstaller.class.getClassLoader();
  }
}
