package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.AgentLog;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.core.HookDemand;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.BridgeDelegate;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.Nexus;
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
 *       It first refuses a JVM whose dispatcher already carries the delegate of another copy of the
 *       agent, before any step that cannot be undone.
 *   <li>{@link BridgeInjector#injectBridge(java.lang.instrument.Instrumentation,
 *       java.nio.file.Path)} packages {@link
 *       com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher} and the types woven
 *       advice references into a temporary JAR and appends it to the bootstrap classpath so that
 *       bootstrap-loaded JDK classes can see it.
 *   <li>{@link ModuleOpener#grantInternalModuleOpens} opens the JDK-internal packages the advice
 *       reads.
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
 * <p>{@link #install} is expected to be called once at startup, from a single thread. {@link
 * #weave} and {@link #uninstall} may run on any thread: the woven groups are guarded by their map's
 * monitor.
 */
public final class JdkInstrumentationInstaller {
  private static final AgentLog LOGGER =
      AgentLog.named(JdkInstrumentationInstaller.class.getName());

  /**
   * ByteBuddy's package, whose classes the agent never touches. Built at run time: the agent jar
   * relocates ByteBuddy, and with it a {@code "net.bytebuddy."} literal, which would then name only
   * the agent's own relocated copy and no longer an application's ByteBuddy.
   */
  private static final String BYTE_BUDDY_PACKAGE_PREFIX = String.join(".", "net", "bytebuddy", "");

  /**
   * The transformer of each hook group woven so far, kept so {@link #uninstall} can reset the
   * transformations. Guarded by its own monitor.
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
   * {@code agentmain} and self-attach.
   *
   * <p>In the classes under {@code clockCallSitePackages}, calls to {@link
   * System#currentTimeMillis()} and {@link System#nanoTime()} are rewritten to go through the
   * clock-skew dispatch once a {@code SYSTEM_CLOCK_MILLIS} or {@code SYSTEM_CLOCK_NANOS} scenario
   * is activated: the methods are JIT intrinsics no advice can reach, their call sites can be
   * rewritten.
   *
   * @param instrumentation the {@link Instrumentation} handle provided by the JVM at agent
   *     attachment
   * @param runtime the runtime the bridge dispatches to
   * @param eager {@code true} to weave every hook group immediately instead of on demand, so no
   *     activation pays the one-time weaving cost
   * @param clockCallSitePackages package (or class-name) prefixes whose clock calls are rewritten,
   *     e.g. {@code com.example.}; {@code java.}, {@code javax.}, {@code jdk.}, {@code sun.} and
   *     {@code com.sun.} prefixes are refused
   * @param sessionHeaderHosts host globs that receive {@code X-Chaos-Session} besides the internal
   *     hosts ({@code *} for all)
   * @param bridgeDir the directory for the bootstrap bridge jar ({@code bridgeDir}), already
   *     checked by the caller; {@code null} for {@code java.io.tmpdir}, then {@code /dev/shm}
   * @throws IllegalArgumentException if a clock prefix names JDK classes
   * @throws IllegalStateException if the bootstrap class path already holds the bridge of another
   *     agent revision, or a copy of the agent loaded by another class loader is installed (nothing
   *     is changed then; when that copy installs while this one is installing, only this one's
   *     module opens stay), or if a later step fails (the delegate and the hooks are removed again)
   */
  public static void install(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean eager,
      final List<String> clockCallSitePackages,
      final List<String> sessionHeaderHosts,
      final java.nio.file.@Nullable Path bridgeDir) {
    clockPackages = validatedClockPackages(clockCallSitePackages);
    // A refused copy must leave no trace: the foreign check runs before the steps that cannot be
    // undone (a jar appended to the bootstrap class path, opened modules). A bridge of another
    // revision comes first: this copy's delegate and advice cannot run against its classes.
    BridgeInjector.requireOwnRevision();
    rejectForeignDelegate(installedDelegateOrNull());
    BridgeInjector.injectBridge(instrumentation, bridgeDir);
    ModuleOpener.grantInternalModuleOpens(instrumentation);
    try {
      installDelegateUnlessForeign(new ChaosBridge(runtime));
      setSessionHeaderHosts(sessionHeaderHosts);
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
      // became active. A failing uninstall must not replace the failure that caused it.
      try {
        uninstall(instrumentation);
      } catch (final RuntimeException | Error uninstallFailure) {
        failure.addSuppressed(uninstallFailure);
      }
      throw failure;
    }
  }

  /**
   * Weaves the hook groups serving {@code operations} that are not woven yet. Each group is its own
   * {@link AgentBuilder} installation, so it retransforms only the classes it targets and can be
   * reset on its own. Groups stay woven until {@link #uninstall}: re-weaving on every activation
   * and stop would retransform core JDK classes over and over in test suites.
   *
   * <p>A group the JVM refuses to retransform is not recorded as woven (the next call that needs it
   * tries again) and is listed in the hook failures.
   *
   * @param instrumentation the instrumentation handle
   * @param operations the operations about to be evaluated
   * @throws IllegalStateException if a group cannot be woven, also when the JVM refuses to
   *     retransform one of its classes
   */
  static void weave(final Instrumentation instrumentation, final Set<OperationType> operations) {
    // The agent's own work (L3): ByteBuddy's reflection while it installs and retransforms is no
    // application call, so a REFLECTION_INVOKE reject cannot fail the weaving.
    BootstrapDispatcher.beginInternalCall();
    try {
      weaveGroups(instrumentation, operations);
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  private static void weaveGroups(
      final Instrumentation instrumentation, final Set<OperationType> operations) {
    synchronized (WOVEN_GROUPS) {
      boolean wovenNow = false;
      try {
        for (final HookGroup group : HookGroup.values()) {
          if (WOVEN_GROUPS.containsKey(group) || !group.serves(operations)) {
            continue;
          }
          final ResettableClassFileTransformer transformer;
          try {
            transformer = group.apply(buildBaseAgentBuilder()).installOn(instrumentation);
          } catch (final RuntimeException failure) {
            // Not recorded as woven: the next activation that needs the group tries again.
            HookCoverage.failed(group.name(), failure);
            throw new IllegalStateException(
                "failed to instrument " + group + " for " + group.operations(), failure);
          }
          WOVEN_GROUPS.put(group, transformer);
          wovenNow = true;
          LOGGER.fine(() -> "chaos hooks woven: " + group);
        }
      } finally {
        // Some JDK classes (the object streams, the socket classes) can be loaded as a side effect
        // of an installOn() while it is inside a JVMTI ClassFileLoadHook for another class; the
        // JVMTI re-entrancy rule then suppresses the hook for those loads, so they escape every
        // transformer, also those of groups woven earlier. Outside any hook, an explicit
        // retransformation applies the missing advice; for a class no group targets it is a
        // no-op. The groups woven before a failing one stay woven, so they need it too.
        if (wovenNow) {
          retransformPostInstallEscapees(instrumentation);
        }
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
        ConcurrencyHooks::applyThreadStartTransformations,
        OperationType.THREAD_START,
        OperationType.VIRTUAL_THREAD_START),
    THREAD_POOL_EXECUTOR(
        ConcurrencyHooks::applyThreadPoolExecutorTransformations,
        OperationType.EXECUTOR_SUBMIT,
        OperationType.EXECUTOR_WORKER_RUN,
        OperationType.EXECUTOR_SHUTDOWN,
        OperationType.EXECUTOR_AWAIT_TERMINATION),
    /**
     * {@code execute} / {@code submit} of a ScheduledThreadPoolExecutor and of the
     * virtual-thread-per-task executor. Woven with SCHEDULE_SUBMIT too: an STPE's own {@code
     * execute} / {@code submit} is an EXECUTOR_SUBMIT, never a SCHEDULE_SUBMIT, whatever else is
     * woven.
     */
    EXECUTOR_SUBMIT(
        ConcurrencyHooks::applyExecutorSubmitTransformations,
        OperationType.EXECUTOR_SUBMIT,
        OperationType.SCHEDULE_SUBMIT),
    SCHEDULED_EXECUTOR(
        ConcurrencyHooks::applyScheduledExecutorTransformations,
        OperationType.SCHEDULE_SUBMIT,
        OperationType.SCHEDULE_TICK),
    FORK_JOIN(ConcurrencyHooks::applyForkJoinTransformations, OperationType.FORK_JOIN_TASK_RUN),
    BLOCKING_QUEUE(
        ConcurrencyHooks::applyBlockingQueueTransformations,
        OperationType.QUEUE_PUT,
        OperationType.QUEUE_OFFER,
        OperationType.QUEUE_TAKE,
        OperationType.QUEUE_POLL),
    COMPLETABLE_FUTURE(
        ConcurrencyHooks::applyCompletableFutureTransformations,
        OperationType.ASYNC_COMPLETE,
        OperationType.ASYNC_COMPLETE_EXCEPTIONALLY),
    COMPLETABLE_FUTURE_CANCEL(
        ConcurrencyHooks::applyCompletableFutureCancelTransformations, OperationType.ASYNC_CANCEL),
    SHUTDOWN_HOOK(
        ConcurrencyHooks::applyShutdownHookTransformations, OperationType.SHUTDOWN_HOOK_REGISTER),
    CLASS_LOADER(
        JvmRuntimeHooks::applyClassLoaderTransformations,
        OperationType.CLASS_LOAD,
        OperationType.RESOURCE_LOAD),
    CLASS_DEFINE(JvmRuntimeHooks::applyClassDefineTransformations, OperationType.CLASS_DEFINE),
    /**
     * The L3a class-loading bracket: raises no operation of its own, and is woven with the
     * operations whose JDK use it hides (FILE_IO and ZIP).
     */
    JDK_INTERNAL_CLASS_LOAD(
        JvmRuntimeHooks::applyJdkInternalClassLoadTransformations,
        OperationType.FILE_IO_READ,
        OperationType.FILE_IO_WRITE,
        OperationType.FILE_IO_SYNC,
        OperationType.FILE_IO_TRUNCATE,
        OperationType.FILE_IO_DELETE,
        OperationType.FILE_IO_MOVE,
        OperationType.FILE_IO_OPEN,
        OperationType.FILE_IO_MAP,
        OperationType.FILE_IO_CLOSE,
        OperationType.FILE_IO_MOVE_TARGET,
        OperationType.ZIP_INFLATE,
        OperationType.ZIP_DEFLATE),
    RUNTIME_LIFECYCLE(
        JvmRuntimeHooks::applyRuntimeLifecycleTransformations,
        OperationType.SYSTEM_GC_REQUEST,
        OperationType.SYSTEM_EXIT_REQUEST),
    REFLECTION_INVOKE(
        JvmRuntimeHooks::applyReflectionInvokeTransformations, OperationType.REFLECTION_INVOKE),
    DIRECT_BUFFER(
        JvmRuntimeHooks::applyDirectBufferTransformations, OperationType.DIRECT_BUFFER_ALLOCATE),
    SERIALIZATION(
        JvmRuntimeHooks::applySerializationTransformations,
        OperationType.OBJECT_SERIALIZE,
        OperationType.OBJECT_DESERIALIZE),
    THREAD_PARK(ConcurrencyHooks::applyLockSupportTransformations, OperationType.THREAD_PARK),
    MONITOR_ENTER(ConcurrencyHooks::applyAqsMonitorTransformations, OperationType.MONITOR_ENTER),
    NIO(
        NetworkHooks::applyNioChannelTransformations,
        OperationType.NIO_SELECTOR_SELECT,
        OperationType.NIO_CHANNEL_READ,
        OperationType.NIO_CHANNEL_WRITE,
        OperationType.NIO_CHANNEL_CONNECT,
        OperationType.NIO_CHANNEL_ACCEPT),
    SOCKET(
        NetworkHooks::applySocketAndStreamTransformations,
        OperationType.SOCKET_CONNECT,
        OperationType.SOCKET_ACCEPT,
        OperationType.SOCKET_READ,
        OperationType.SOCKET_WRITE,
        OperationType.SOCKET_CLOSE,
        OperationType.SOCKET_BIND,
        OperationType.SOCKET_SHUTDOWN),
    /** UDP: datagram sends and receives of {@code DatagramSocket} and {@code DatagramChannel}. */
    DATAGRAM(
        NetworkHooks::applyDatagramTransformations,
        OperationType.DATAGRAM_SEND,
        OperationType.DATAGRAM_RECEIVE),
    /** The same socket events, raised by {@code SocketChannel} and {@code ServerSocketChannel}. */
    SOCKET_CHANNEL(
        NetworkHooks::applySocketChannelTransformations,
        OperationType.SOCKET_CONNECT,
        OperationType.SOCKET_ACCEPT,
        OperationType.SOCKET_READ,
        OperationType.SOCKET_WRITE,
        OperationType.SOCKET_CLOSE,
        OperationType.SOCKET_SHUTDOWN),
    ZIP(
        JvmRuntimeHooks::applyZipTransformations,
        OperationType.ZIP_INFLATE,
        OperationType.ZIP_DEFLATE),
    THREAD_LOCAL(
        ConcurrencyHooks::applyThreadLocalTransformations,
        OperationType.THREAD_LOCAL_GET,
        OperationType.THREAD_LOCAL_SET),
    CLOCK_CALL_SITES(
        ClockHooks::applyClockCallSiteTransformations,
        OperationType.SYSTEM_CLOCK_MILLIS,
        OperationType.SYSTEM_CLOCK_NANOS),
    TIME_API(
        ClockHooks::applyTimeApiTransformations,
        OperationType.INSTANT_NOW,
        OperationType.CLOCK_READ,
        OperationType.LOCAL_DATE_TIME_NOW,
        OperationType.ZONED_DATE_TIME_NOW,
        OperationType.DATE_NEW),
    SSL_HANDSHAKE(NetworkHooks::applySslHandshakeTransformations, OperationType.SSL_HANDSHAKE),
    KAFKA(
        ClientLibraryHooks::applyKafkaTransformations,
        OperationType.MESSAGE_SEND,
        OperationType.MESSAGE_RECEIVE),
    GRPC(ClientLibraryHooks::applyGrpcTransformations, OperationType.RPC_CALL),
    REDIS(ClientLibraryHooks::applyRedisTransformations, OperationType.REDIS_COMMAND),
    R2DBC(
        JdkInstrumentationInstaller::applyR2dbcTransformations,
        OperationType.R2DBC_STATEMENT_EXECUTE),
    AWS_SDK(ClientLibraryHooks::applyAwsSdkTransformations, OperationType.AWS_SDK_CALL),
    NETTY(
        ClientLibraryHooks::applyNettyTransformations,
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
   *     <p>Every step runs even when an earlier one fails: the delegate is cleared, the
   *     transformers are removed and the hook coverage is reset, so a later install starts empty.
   * @return {@code true} if a transformer was reset or this copy's delegate was cleared; {@code
   *     false} if there was nothing to undo
   * @throws IllegalStateException after every step ran, if the JVM refused to retransform a class
   *     back (it keeps the advice) or another step failed: the first failure, with the later ones
   *     attached as suppressed
   */
  public static boolean uninstall(final Instrumentation instrumentation) {
    final List<RuntimeException> failures = new java.util.ArrayList<>();
    boolean undone = false;
    try {
      undone = clearOwnDelegate();
    } catch (final RuntimeException failure) {
      failures.add(failure);
    }
    try {
      undone |= MethodWeaver.reset(instrumentation);
    } catch (final RuntimeException failure) {
      failures.add(failure);
    }
    synchronized (WOVEN_GROUPS) {
      for (final ResettableClassFileTransformer transformer : WOVEN_GROUPS.values()) {
        try {
          reset(transformer, instrumentation);
        } catch (final RuntimeException failure) {
          failures.add(failure);
        }
        undone = true;
      }
      WOVEN_GROUPS.clear();
    }
    HookCoverage.reset();
    BridgeVisibility.reset();
    if (!failures.isEmpty()) {
      final RuntimeException first = failures.get(0);
      failures.subList(1, failures.size()).forEach(first::addSuppressed);
      throw first;
    }
    return undone;
  }

  /**
   * Removes {@code transformer} and retransforms its classes back, failing when the JVM refuses
   * one: the default listener would ignore that, and the class would keep its advice silently.
   *
   * @throws IllegalStateException if a class could not be retransformed
   */
  static void reset(
      final ResettableClassFileTransformer transformer, final Instrumentation instrumentation) {
    BootstrapDispatcher.beginInternalCall();
    try {
      transformer.reset(
          instrumentation,
          AgentBuilder.RedefinitionStrategy.RETRANSFORMATION,
          AgentBuilder.RedefinitionStrategy.BatchAllocator.ForTotal.INSTANCE,
          AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST);
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /**
   * Runs every transformation of a transformer as the agent's own work (L3): a class that loads on
   * an application thread is transformed with the reentrancy depth raised, so ByteBuddy's own
   * reflection is no {@code REFLECTION_INVOKE} target and no other scenario applies to it either.
   */
  static final AgentBuilder.TransformerDecorator AGENT_WORK =
      transformer ->
          ResettableClassFileTransformer.WithDelegation.of(
              transformer,
              new ResettableClassFileTransformer.WithDelegation.Callback<Boolean>() {
                @Override
                public Boolean onBeforeTransform(
                    final @Nullable JavaModule module,
                    final @Nullable ClassLoader classLoader,
                    final @Nullable String internalName,
                    final @Nullable Class<?> classBeingRedefined,
                    final java.security.@Nullable ProtectionDomain protectionDomain,
                    final byte[] classFile) {
                  BootstrapDispatcher.beginInternalCall();
                  return Boolean.TRUE;
                }

                @Override
                public void onAfterTransform(
                    final @Nullable Boolean begun,
                    final @Nullable JavaModule module,
                    final @Nullable ClassLoader classLoader,
                    final @Nullable String internalName,
                    final @Nullable Class<?> classBeingRedefined,
                    final java.security.@Nullable ProtectionDomain protectionDomain,
                    final byte[] classFile) {
                  BootstrapDispatcher.endInternalCall();
                }
              });

  /**
   * Refuses to install when the JVM-wide dispatcher already carries a delegate from a copy of the
   * agent loaded by a different class loader (for example an agent jar on the system class path
   * plus a starter bundled child-first in a web application). Installing would replace that
   * delegate: the first copy's startup plan, scenarios and diagnostics would silently stop
   * receiving any call.
   */
  private static void rejectForeignDelegate(final @Nullable Object existing) {
    if (existing != null && !ownLoader(existing)) {
      throw new IllegalStateException(
          "the chaos agent is already installed in this JVM by another class loader ("
              + existing.getClass().getClassLoader()
              + "); only one copy of the agent can be active per JVM. Remove the duplicate"
              + " chaos-agent jar from the application, or use the copy that is already installed");
    }
  }

  /**
   * Installs {@code bridgeDelegate} unless a copy of the agent loaded by another class loader has
   * installed its delegate since {@link #install}'s first check: two copies (two web applications
   * that each bundle the agent) can both pass that check. The check and the install hold the
   * monitor of the bootstrap {@code BootstrapDispatcher}, which its {@code install} takes, as
   * {@link #connectDispatcher} does.
   */
  private static void installDelegateUnlessForeign(final Object bridgeDelegate) {
    final Class<?> dispatcher;
    try {
      dispatcher = bootstrapDispatcherClass();
    } catch (final ClassNotFoundException notInjected) {
      throw new IllegalStateException("the bridge is not on the bootstrap class path", notInjected);
    }
    synchronized (dispatcher) {
      rejectForeignDelegate(installedDelegateOrNull());
      installDelegate(bridgeDelegate);
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

  /**
   * Hands the session-header hosts to the bootstrap copy of {@code SessionHeaderPolicy}, the one
   * the woven HTTP advice reads: under a child-first class loader a direct call would reach this
   * loader's own copy instead.
   */
  private static void setSessionHeaderHosts(final List<String> hosts) {
    try {
      Class.forName("com.macstab.chaos.jvm.instrumentation.bridge.SessionHeaderPolicy", true, null)
          .getMethod("setExtraHosts", List.class)
          .invoke(null, hosts);
    } catch (final ReflectiveOperationException exception) {
      throw new IllegalStateException("failed to set the session header hosts", exception);
    }
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
   * result disconnects it again, unless another delegate replaced it in the meantime.
   *
   * <p>For benchmarks and tests on the plain (thin) {@code chaos-agent-instrumentation-jdk} classes
   * only: it installs into the dispatcher this class links against, which is the bootstrap copy
   * only when no agent jar injected one. The agent jar keeps the dispatcher out of its own loader's
   * view ({@code META-INF/macstab-chaos-bridge/}), so there it resolves only once {@link #install}
   * injected the bridge.
   *
   * @param runtime the runtime that receives the dispatches
   * @return a handle that uninstalls this delegate when closed
   * @throws IllegalStateException if a copy of the agent loaded by another class loader is
   *     installed
   * @throws Exception if the method handles cannot be built
   */
  @Internal
  // The monitor of BootstrapDispatcher.install: the check and the install (or clear) are one step.
  @SuppressWarnings("LockOnNonEnclosingClassLiteral")
  public static AutoCloseable connectDispatcher(final ChaosRuntime runtime) throws Exception {
    final ChaosBridge bridge = new ChaosBridge(runtime);
    final MethodHandle[] methodHandles = buildMethodHandles();
    synchronized (BootstrapDispatcher.class) {
      rejectForeignDelegate(BootstrapDispatcher.installedDelegate());
      BootstrapDispatcher.install(bridge, methodHandles);
    }
    return () -> {
      synchronized (BootstrapDispatcher.class) {
        if (BootstrapDispatcher.installedDelegate() == bridge) {
          BootstrapDispatcher.install(null, null);
        }
      }
    };
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
   * Instruments classes that may not be present in every application (OkHttp, HikariCP, c3p0,
   * Reactor Netty) or every JDK.
   *
   * <p>The type is matched by name only. ByteBuddy evaluates the matcher whenever a class is
   * defined, by any class loader, so a library loaded later by an application class loader (for
   * example Spring Boot's fat-jar launcher, which does not exist yet when {@code premain} runs) is
   * still woven. An absent library costs one string comparison per class definition and nothing
   * else. Advice applied this way only uses {@code Object}-typed parameters and reflective access,
   * so the libraries are not needed on the agent's classpath.
   *
   * <p>The class is not probed with {@code Class.forName} at install time: under {@code premain} in
   * a fat jar such a probe always fails, so OkHttp, HikariCP, c3p0 and Reactor Netty chaos would
   * silently never apply.
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
   * BridgeInjector#injectBridge} appends it) and this class is loaded by the agent classloader, the
   * two share no common type. The method therefore resolves {@code BootstrapDispatcher} via {@link
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
    // CompletableFuture's nested timer classes load while CompletableFuture itself is
    // retransformed, so they escape the L3a timer hooks of the same groups. Retransforming the
    // JDK 21 Delayer resolves its ScheduledThreadPoolExecutor: the pool types are loaded first,
    // here, so they do not load (and escape) inside that retransformation.
    "java.util.concurrent.ThreadPoolExecutor",
    "java.util.concurrent.ScheduledThreadPoolExecutor",
    "java.util.concurrent.CompletableFuture$Timeout",
    "java.util.concurrent.CompletableFuture$Delayer",
    "java.util.concurrent.CompletableFuture$DelayedCompleter",
  };

  /**
   * Re-runs the {@link java.lang.instrument.ClassFileTransformer} for classes that were loaded as a
   * side-effect during {@link AgentBuilder#installOn} and so escaped the original transformer pass.
   * See {@link #POST_INSTALL_RETRANSFORM_TARGETS} for the list and rationale.
   */
  private static void retransformPostInstallEscapees(final Instrumentation instrumentation) {
    // A target not loaded yet is loaded here (not initialized): it is one that loads inside
    // another class's transformation, where it would escape the transformers again.
    final List<Class<?>> loaded = new java.util.ArrayList<>();
    for (final String name : POST_INSTALL_RETRANSFORM_TARGETS) {
      try {
        final Class<?> target = Class.forName(name, false, null);
        if (instrumentation.isModifiableClass(target)) {
          loaded.add(target);
        }
      } catch (final ClassNotFoundException absent) {
        // not in this JDK
      }
    }
    if (loaded.isEmpty()) {
      return;
    }
    try {
      instrumentation.retransformClasses(loaded.toArray(new Class<?>[0]));
    } catch (final Throwable batchFailure) {
      // One refused class fails the whole call: retry each, so the others still get their advice.
      for (final Class<?> escapee : loaded) {
        try {
          instrumentation.retransformClasses(escapee);
        } catch (final Throwable failure) {
          HookCoverage.failed(escapee.getName(), failure);
          LOGGER.warning(() -> "failed to retransform " + escapee.getName() + ": " + failure);
        }
      }
    }
  }

  /**
   * Applies {@code advice} to the methods {@code methods} matches, recording whether it matched any
   * ({@link HookCoverage}): a hook whose JDK method changed is reported instead of going inert.
   *
   * <p>Bridge methods are never advised: a bridge only calls the method it bridges to, which is the
   * one call to evaluate. A hook that would match a bridge alone is reported as unmatched.
   */
  static AsmVisitorWrapper advise(
      final Class<?> advice, final ElementMatcher<? super MethodDescription> methods) {
    return Advice.to(advice)
        .on(
            ElementMatchers.not(ElementMatchers.isBridge())
                .and(HookCoverage.track(advice, methods)));
  }

  /**
   * Creates an {@link AgentBuilder} without ByteBuddy's {@link Nexus}. The default builder
   * initialises the Nexus, whose class injector reaches for {@code sun.misc.Unsafe}: JDK 24 and
   * later print a deprecation warning for it on every start (JEP 498). The hooks are inlined advice
   * and register no live type initializer, so the Nexus is never needed. The switch is set only
   * while the builder is created; the Nexus reads it once, when the builder first touches it. In
   * the agent jar the property name is relocated with ByteBuddy, so it concerns the agent's copy
   * only. Package-private as a test seam.
   */
  static AgentBuilder newAgentBuilder() {
    final String previous = System.getProperty(Nexus.PROPERTY);
    System.setProperty(Nexus.PROPERTY, "true");
    try {
      return new AgentBuilder.Default();
    } finally {
      if (previous == null) {
        System.clearProperty(Nexus.PROPERTY);
      } else {
        System.setProperty(Nexus.PROPERTY, previous);
      }
    }
  }

  /**
   * The listener that records and logs a type whose transformation failed ({@link
   * HookCoverage#failed(String, Throwable)}).
   *
   * @return the listener
   */
  static AgentBuilder.Listener errorListener() {
    return new AgentBuilder.Listener.Adapter() {
      @Override
      public void onError(
          final String typeName,
          final ClassLoader classLoader,
          final JavaModule module,
          final boolean loaded,
          final Throwable throwable) {
        HookCoverage.failed(typeName, throwable);
        LOGGER.warning(() -> "chaos instrumentation failed for " + typeName + ": " + throwable);
      }
    };
  }

  /**
   * Builds the base {@link AgentBuilder} with retransformation strategy, failing on a refused
   * retransformation, error logging, transformations run as the agent's own work ({@link
   * #AGENT_WORK}), and ignore rules for ByteBuddy and chaos-agent internal packages and for class
   * loaders with a bootstrap bridge copy of their own ({@link BridgeVisibility}).
   */
  private static AgentBuilder buildBaseAgentBuilder() {
    return newAgentBuilder()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        // A class the JVM refuses to retransform fails the installation (ByteBuddy removes the
        // transformer and throws) instead of leaving a group recorded as woven with inert hooks.
        .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
        .with(errorListener())
        .with(AGENT_WORK)
        .ignore(
            ElementMatchers.nameStartsWith(BYTE_BUDDY_PACKAGE_PREFIX)
                .or(ElementMatchers.nameStartsWith("com.macstab.chaos.jvm.")))
        // Nor the classes of a loader with a bridge copy of its own: their advice would link
        // against a dispatcher without the delegate.
        .or(ElementMatchers.any(), BridgeVisibility.OWN_BRIDGE_COPY);
  }

  /**
   * Registers HTTP client interception: Java {@code java.net.http.HttpClient} (JDK 11+), OkHttp,
   * Apache HC 4.x, Apache HC 5.x, Reactor Netty.
   *
   * <p>The library targets are matched by name whenever they are loaded, by any class loader
   * ({@link #instrumentOptional}; the Apache clients by package prefix and superclass name, {@link
   * #apacheDoExecute}). Advice classes only use Object-typed arguments and reflective URL
   * extraction, so the compileOnly dependencies (OkHttp, Apache HC, Reactor Netty) are not required
   * at runtime.
   *
   * <p>Java 11+ {@code HttpClient} is implemented by {@code jdk.internal.net.http.HttpClientImpl},
   * which lives in the non-exported {@code java.net.http/jdk.internal.net.http} package. Two pieces
   * are required to instrument it without users having to set {@code --add-opens} JVM flags:
   *
   * <ol>
   *   <li>Module access — granted automatically by {@link ModuleOpener#grantInternalModuleOpens}
   *       (programmatic {@code redefineModule}) plus the {@code Add-Opens} attribute in the agent
   *       JAR's {@code MANIFEST.MF}.
   *   <li>Its own {@link AgentBuilder} pass, separate from the other transformations of its hook
   *       group.
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
                    // 4.12: getResponseWithInterceptorChain$okhttp(); 3.x and 5.x unverified.
                    .visit(
                        advise(
                            HttpClientAdvice.OkHttpChainAdvice.class,
                            ElementMatchers.nameStartsWith("getResponseWithInterceptorChain")
                                .and(ElementMatchers.takesArguments(0)))),
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall");

    // Apache HC 4.x and 5.x: every public execute / executeOpen overload of CloseableHttpClient
    // reaches the protected doExecute(HttpHost, request, HttpContext) with the caller's host, and
    // response handlers run after it returns. The implementations are matched by package and
    // superclass (InternalHttpClient, MinimalHttpClient; in HC4 also the abstract
    // AbstractHttpClient, whose doExecute is final, so DefaultHttpClient and its siblings inherit
    // it); the abstract filter is on the method, not the type. A CloseableHttpClient subclass in
    // another package is not hooked.
    agentBuilder =
        apacheDoExecute(
            agentBuilder,
            HttpClientAdvice.ApacheHc4DoExecuteAdvice.class,
            "org.apache.http.impl.client.");
    agentBuilder =
        apacheDoExecute(
            agentBuilder,
            HttpClientAdvice.ApacheHc5DoExecuteAdvice.class,
            "org.apache.hc.client5.http.impl.classic.");

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
    // Reactor Netty: one evaluation per subscription of the request's Mono (1.1.21).
    return instrumentOptional(
        agentBuilder,
        typeBuilder ->
            typeBuilder.visit(
                advise(
                    HttpClientAdvice.ReactorNettySubscribeAdvice.class,
                    ElementMatchers.named("subscribe").and(ElementMatchers.takesArguments(1)))),
        "reactor.netty.http.client.HttpClientConnect$MonoHttpConnect");
  }

  /**
   * Hooks {@code doExecute(HttpHost, request, HttpContext)} of the Apache HttpClient {@code
   * CloseableHttpClient} subclasses in {@code clientPackage}. The package prefix keeps the
   * superclass walk off every other class.
   */
  private static AgentBuilder apacheDoExecute(
      final AgentBuilder agentBuilder, final Class<?> advice, final String clientPackage) {
    return agentBuilder
        .type(
            ElementMatchers.nameStartsWith(clientPackage)
                .and(
                    TypeMatchers.superClass(
                        ElementMatchers.named(clientPackage + "CloseableHttpClient"))))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        advice,
                        ElementMatchers.named("doExecute")
                            .and(ElementMatchers.takesArguments(3))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))));
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
            TypeMatchers.subTypeOf(java.sql.Statement.class)
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
                                .and(ElementMatchers.takesArgument(0, String.class))))
                    // A batch of any Statement, prepared or not: one execution without SQL text.
                    .visit(
                        advise(
                            JdbcAdvice.PreparedStatementExecuteAdvice.class,
                            ElementMatchers.namedOneOf("executeBatch", "executeLargeBatch")
                                .and(ElementMatchers.takesArguments(0))
                                .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        .type(
            TypeMatchers.subTypeOf(java.sql.PreparedStatement.class)
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
            TypeMatchers.subTypeOf(java.sql.Connection.class)
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
            TypeMatchers.subTypeOf(javax.management.MBeanServer.class)
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
                                .and(ElementMatchers.takesArguments(2))))
                    .visit(
                        advise(
                            JvmRuntimeAdvice.JmxGetAttrsAdvice.class,
                            ElementMatchers.named("getAttributes")
                                .and(
                                    ElementMatchers.takesArguments(
                                        javax.management.ObjectName.class, String[].class)))));
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
                            DnsAdvice.DnsResolveAdvice.class,
                            ElementMatchers.named("getByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            DnsAdvice.DnsResolveAdvice.class,
                            ElementMatchers.named("getAllByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            DnsAdvice.DnsAnswerAdvice.class,
                            ElementMatchers.named("getAllByName")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(String.class))))
                    .visit(
                        advise(
                            DnsAdvice.DnsLocalHostAdvice.class,
                            ElementMatchers.named("getLocalHost")
                                .and(ElementMatchers.isStatic())
                                .and(ElementMatchers.takesArguments(0))))
                    // getHostName and getCanonicalHostName both look the name up here.
                    .visit(
                        advise(
                            DnsAdvice.DnsReverseAdvice.class,
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
                            FileIoAdvice.FileReadByteAdvice.class,
                            ElementMatchers.named("read").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.FileReadArrayAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArguments(byte[].class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileReadRangeAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    // Compound reads: one read, however many reads the stream makes for them.
                    .visit(
                        advise(
                            FileIoAdvice.FileReadAllBytesAdvice.class,
                            ElementMatchers.named("readAllBytes")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.FileReadNBytesAdvice.class,
                            ElementMatchers.named("readNBytes")
                                .and(ElementMatchers.takesArguments(int.class)))))
        .type(ElementMatchers.named("java.io.FileOutputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            FileIoAdvice.FileWriteByteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileWriteArrayAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(byte[].class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileWriteRangeAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class)))));
  }

  /** R2DBC {@code Statement.execute()} in any driver. */
  private static AgentBuilder applyR2dbcTransformations(final AgentBuilder builder) {
    return builder
        .type(
            TypeMatchers.superType(ElementMatchers.named("io.r2dbc.spi.Statement"))
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

  /**
   * File channels, {@code RandomAccessFile} and fsync. {@code FileChannelImpl} is hooked at its
   * public methods ({@code read}/{@code write} of one buffer, positional or not, and of a buffer
   * array; {@code map}, {@code transferTo}/{@code transferFrom}, {@code force}, {@code truncate},
   * {@code implCloseChannel}); {@code RandomAccessFile} at {@code read()}, {@code write(int)},
   * {@code readLine()}, {@code setLength} and its private funnels {@code readBytes}/{@code
   * writeBytes}, so a public {@code read}/{@code write} overload delegating to a funnel is one
   * call.
   */
  private static AgentBuilder applyFileChannelInterception(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(ElementMatchers.named("sun.nio.ch.FileChannelImpl"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(ElementMatchers.takesArgument(0, java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArgument(0, java.nio.ByteBuffer.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelScatterReadAdvice.class,
                            ElementMatchers.named("read")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelGatherWriteAdvice.class,
                            ElementMatchers.named("write")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.ByteBuffer[].class, int.class, int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelMapAdvice.class,
                            ElementMatchers.named("map")
                                .and(
                                    ElementMatchers.takesArgument(
                                        0, java.nio.channels.FileChannel.MapMode.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelTransferToAdvice.class,
                            ElementMatchers.named("transferTo")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class,
                                        long.class,
                                        java.nio.channels.WritableByteChannel.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelTransferFromAdvice.class,
                            ElementMatchers.named("transferFrom")
                                .and(
                                    ElementMatchers.takesArguments(
                                        java.nio.channels.ReadableByteChannel.class,
                                        long.class,
                                        long.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelCloseAdvice.class,
                            ElementMatchers.named("implCloseChannel")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.FileChannelForceAdvice.class,
                            ElementMatchers.named("force")
                                .and(ElementMatchers.takesArguments(boolean.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileTruncateAdvice.class,
                            ElementMatchers.named("truncate")
                                .and(ElementMatchers.takesArguments(long.class)))))
        .type(ElementMatchers.named("java.io.RandomAccessFile"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            FileIoAdvice.RandomAccessReadByteAdvice.class,
                            ElementMatchers.named("read").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.RandomAccessReadLineAdvice.class,
                            ElementMatchers.named("readLine")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.RandomAccessReadRangeAdvice.class,
                            ElementMatchers.named("readBytes")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileWriteByteAdvice.class,
                            ElementMatchers.named("write")
                                .and(ElementMatchers.takesArguments(int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileWriteRangeAdvice.class,
                            ElementMatchers.named("writeBytes")
                                .and(
                                    ElementMatchers.takesArguments(
                                        byte[].class, int.class, int.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FileTruncateAdvice.class,
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
                            FileIoAdvice.FilesDeleteAdvice.class,
                            ElementMatchers.named("delete")
                                .and(ElementMatchers.takesArguments(java.nio.file.Path.class))))
                    .visit(
                        advise(
                            FileIoAdvice.FilesDeleteIfExistsAdvice.class,
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
                            FileIoAdvice.FileOpenAdvice.class,
                            ElementMatchers.named("open")
                                .and(ElementMatchers.takesArgument(0, String.class))
                                .and(ElementMatchers.not(ElementMatchers.isNative()))))
                    .visit(
                        advise(
                            FileIoAdvice.FileStreamCloseAdvice.class,
                            ElementMatchers.named("close").and(ElementMatchers.takesArguments(0)))))
        // Opens of java.nio: Files.newInputStream / newOutputStream / newByteChannel and
        // FileChannel.open reach the platform provider's newByteChannel or newFileChannel.
        .type(
            ElementMatchers.nameStartsWith("sun.nio.fs.")
                .and(TypeMatchers.subTypeOf(java.nio.file.spi.FileSystemProvider.class)))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        FileIoAdvice.FilesOpenAdvice.class,
                        ElementMatchers.namedOneOf("newByteChannel", "newFileChannel")
                            .and(ElementMatchers.takesArguments(3))
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))
                            .and(ElementMatchers.not(ElementMatchers.isAbstract())))))
        // Files.move: each platform provider implements move itself (UnixFileSystemProvider,
        // WindowsFileSystemProvider); other providers (zip file systems) are not hooked.
        .type(
            ElementMatchers.nameStartsWith("sun.nio.fs.")
                .and(TypeMatchers.subTypeOf(java.nio.file.spi.FileSystemProvider.class)))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        advise(
                            FileIoAdvice.FilesMoveAdvice.class,
                            ElementMatchers.named("move")
                                .and(ElementMatchers.takesArguments(3))
                                .and(ElementMatchers.not(ElementMatchers.isAbstract()))))
                    .visit(
                        advise(
                            FileIoAdvice.FilesCopyAdvice.class,
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
                            FileIoAdvice.FileDeleteAdvice.class,
                            ElementMatchers.named("delete").and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            FileIoAdvice.FileRenameAdvice.class,
                            ElementMatchers.named("renameTo")
                                .and(ElementMatchers.takesArguments(java.io.File.class)))))
        // msync of a mapped file region.
        .type(ElementMatchers.named("java.nio.MappedByteBuffer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        FileIoAdvice.MappedBufferForceAdvice.class,
                        ElementMatchers.named("force")
                            .and(ElementMatchers.takesArguments(int.class, int.class)))))
        .type(ElementMatchers.named("java.io.FileDescriptor"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        FileIoAdvice.FileDescriptorSyncAdvice.class,
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
                        ProcessAdvice.ProcessStartAdvice.class,
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
                            ProcessAdvice.ProcessWaitAdvice.class,
                            ElementMatchers.named("waitFor")
                                .and(ElementMatchers.takesArguments(0))))
                    .visit(
                        advise(
                            ProcessAdvice.ProcessTimedWaitAdvice.class,
                            ElementMatchers.named("waitFor")
                                .and(
                                    ElementMatchers.takesArguments(
                                        long.class, java.util.concurrent.TimeUnit.class)))));
  }

  /** Prefixes whose clock call sites are rewritten; empty when the feature is off. */
  static volatile List<String> clockPackages = List.of();

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
   * Whether {@code delegate} was loaded by this installer's class loader (identity, not equals).
   */
  @SuppressWarnings("ReferenceEquality")
  private static boolean ownLoader(final Object delegate) {
    return delegate.getClass().getClassLoader()
        == JdkInstrumentationInstaller.class.getClassLoader();
  }
}
