package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every terminal effect the validator accepts on an operation reaches the call: invoking the
 * operation's {@link ChaosRuntime} entry point, the one the instrumentation advice calls, either
 * changes what the call does (another return value, a thrown exception, a mutated argument or
 * buffer, a returned replacement such as a redirect address or a rewritten host, or a wait of at
 * least the call's own timeout), or the scenario does not count the call as applied.
 *
 * <p>The failure this guards against: an effect passes {@link CompatibilityValidator}, {@code
 * DispatchEngine.terminalActionFor} builds a terminal action for it, the scenario counts the call
 * as applied and publishes APPLIED, and the dispatch method then hands the decision to a path that
 * ignores that kind of terminal, so the call runs unchanged. A timeout on {@code SOCKET_CONNECT}
 * did exactly this.
 *
 * <p>The pairs are enumerated from the code, not listed by hand: every {@link OperationType} except
 * {@code LIFECYCLE}, with a selector that selects only that operation (built by reflection over the
 * selector factories), times one instance of every per-call effect. A pair is checked when {@link
 * ChaosRuntime#activate(ChaosScenario)} accepts it. Each check runs the operation once without a
 * scenario and once with it, and compares the two outcomes.
 *
 * <p>Not checked here:
 *
 * <ul>
 *   <li>delay and gate: {@code DispatchEngine.applyDelayAndGate} applies them to every operation in
 *       the same way, before any terminal action, so they cannot be dropped per operation;
 *   <li>stressors: they run in the background from start to stop and are never evaluated per call.
 * </ul>
 */
@DisplayName("Every effect the validator accepts reaches the call")
class EffectReachesTheCallTest {

  /** The timeout of the calls that have one (SO_TIMEOUT, connect, select, process wait). */
  private static final int TIMEOUT_MILLIS = 50;

  /** The bytes a read asks for or a write hands over. */
  private static final int LENGTH = 64;

  private static final String SCENARIO_ID = "reaches-the-call";

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  private static final int PEER_PORT = 9;

  /**
   * Pairs whose terminal action is dropped although the scenario counts as applied, by their
   * display name. Each is a confirmed bug in the dispatch; the case asserts that it is still
   * dropped, so a fix fails it until the entry is removed here.
   */
  private static final Set<String> KNOWN_DROPPED = Set.of();

  /**
   * The operations on which every selector refuses a reject, by design: the real call never fails
   * that way, or failing it there would break the caller instead of simulating a fault. The
   * enumeration skips a refused pair, so a new refusal would drop the operation's reject case
   * silently; it must be added here on purpose.
   */
  private static final Set<OperationType> KNOWN_REFUSED =
      EnumSet.of(
          OperationType.ASYNC_CANCEL,
          OperationType.CLOCK_READ,
          OperationType.DATE_NEW,
          OperationType.EXECUTOR_SHUTDOWN,
          OperationType.EXECUTOR_WORKER_RUN,
          OperationType.FORK_JOIN_TASK_RUN,
          OperationType.INSTANT_NOW,
          OperationType.LOCAL_DATE_TIME_NOW,
          OperationType.MONITOR_ENTER,
          OperationType.NETTY_CHANNEL_READ,
          OperationType.QUEUE_POLL,
          OperationType.QUEUE_TAKE,
          OperationType.SYSTEM_CLOCK_MILLIS,
          OperationType.SYSTEM_CLOCK_NANOS,
          OperationType.SYSTEM_EXIT_REQUEST,
          OperationType.SYSTEM_GC_REQUEST,
          OperationType.THREAD_LOCAL_GET,
          OperationType.THREAD_LOCAL_SET,
          OperationType.THREAD_PARK,
          OperationType.ZIP_DEFLATE,
          OperationType.ZONED_DATE_TIME_NOW);

  /**
   * Operations that cannot be invoked from a unit test, with the reason. Every other operation
   * except {@code LIFECYCLE} must have an invoker in {@link #INVOKERS}. Empty: every operation's
   * entry point is a public method of {@link ChaosRuntime} that runs without the agent installed.
   */
  private static final Map<OperationType, String> NOT_INVOCABLE = Map.of();

  /** One instance of every per-call effect, by the name a case is displayed with. */
  private static final Map<String, ChaosEffect> EFFECTS = new LinkedHashMap<>();

  static {
    EFFECTS.put("reject", ChaosEffect.reject("chaos"));
    EFFECTS.put("suppress", ChaosEffect.suppress());
    EFFECTS.put(
        "exceptionalCompletion",
        ChaosEffect.exceptionalCompletion(ChaosEffect.FailureKind.TIMEOUT, "chaos"));
    EFFECTS.put("injectException", ChaosEffect.injectException("java.io.IOException", "chaos"));
    EFFECTS.put(
        "returnValueCorruption",
        ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL));
    EFFECTS.put("timeout", ChaosEffect.timeout());
    for (final ChaosEffect.Errno errno : ChaosEffect.Errno.values()) {
      EFFECTS.put("errno(" + errno.name() + ")", ChaosEffect.errno(errno));
    }
    EFFECTS.put("trickle", ChaosEffect.trickle(16));
    EFFECTS.put("corruptRead", ChaosEffect.corruptRead());
    EFFECTS.put("tornWrite", ChaosEffect.tornWrite());
    EFFECTS.put("connectionReset", ChaosEffect.connectionReset());
    // An override always changes the answer; a shuffle of a short answer can keep its order.
    EFFECTS.put("dnsAnswer", ChaosEffect.dnsOverride("192.0.2.1"));
    EFFECTS.put("dnsRewrite", ChaosEffect.dnsRewrite("other.internal"));
    // An IP literal: building the redirect address makes no lookup.
    EFFECTS.put("redirect", ChaosEffect.redirect("127.0.0.2", 7));
    EFFECTS.put("spuriousWakeup", ChaosEffect.spuriousWakeup());
    EFFECTS.put(
        "clockSkew", ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FIXED));
  }

  /** Calls an operation's entry point and returns what the caller observes. */
  @FunctionalInterface
  interface Invoker {
    Object invoke(ChaosRuntime runtime) throws Throwable;
  }

  /** For each operation, a call of the entry point its advice calls, with representative values. */
  static final Map<OperationType, Invoker> INVOKERS = new EnumMap<>(OperationType.class);

  static {
    // Threads
    INVOKERS.put(
        OperationType.THREAD_START,
        runtime -> {
          runtime.beforeThreadStart(new Thread(() -> {}, "probe"), false);
          return "started";
        });
    INVOKERS.put(
        OperationType.VIRTUAL_THREAD_START,
        runtime -> {
          runtime.beforeThreadStart(Thread.ofVirtual().name("probe").unstarted(() -> {}), false);
          return "started";
        });
    INVOKERS.put(OperationType.THREAD_SLEEP, runtime -> "skip " + runtime.beforeThreadSleep(1L));
    INVOKERS.put(
        OperationType.MONITOR_ENTER,
        runtime -> {
          runtime.beforeMonitorEnter(new Object());
          return "entered";
        });
    INVOKERS.put(
        OperationType.THREAD_PARK,
        runtime -> {
          runtime.beforeThreadPark();
          return "parked";
        });
    INVOKERS.put(
        OperationType.THREAD_LOCAL_GET,
        runtime -> {
          final ThreadLocal<String> local = ThreadLocal.withInitial(() -> "value");
          return runtime.beforeThreadLocalGet(local) ? null : local.get();
        });
    INVOKERS.put(
        OperationType.THREAD_LOCAL_SET,
        runtime -> {
          final ThreadLocal<String> local = new ThreadLocal<>();
          if (!runtime.beforeThreadLocalSet(local, "value")) {
            local.set("value");
          }
          final String stored = local.get();
          local.remove();
          return stored;
        });

    // Executors, scheduling, fork-join
    INVOKERS.put(OperationType.EXECUTOR_SUBMIT, runtime -> submit(runtime, "EXECUTOR_SUBMIT"));
    INVOKERS.put(OperationType.SCHEDULE_SUBMIT, runtime -> submit(runtime, "SCHEDULE_SUBMIT"));
    INVOKERS.put(
        OperationType.EXECUTOR_WORKER_RUN,
        runtime -> {
          runtime.beforeWorkerRun(new Object(), Thread.currentThread(), () -> {});
          return "ran";
        });
    INVOKERS.put(
        OperationType.EXECUTOR_SHUTDOWN,
        runtime -> {
          runtime.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", new Object(), 0L);
          return "shut down";
        });
    INVOKERS.put(
        OperationType.EXECUTOR_AWAIT_TERMINATION,
        runtime -> {
          runtime.beforeExecutorShutdown(
              "EXECUTOR_AWAIT_TERMINATION", new Object(), TIMEOUT_MILLIS);
          return "awaited";
        });
    INVOKERS.put(
        OperationType.FORK_JOIN_TASK_RUN,
        runtime -> {
          runtime.beforeForkJoinTaskRun(ForkJoinTask.adapt(() -> {}));
          return "ran";
        });
    INVOKERS.put(
        OperationType.SCHEDULE_TICK,
        runtime ->
            "proceed " + runtime.beforeScheduledTick(new Object(), (Runnable) () -> {}, true));
    INVOKERS.put(
        OperationType.SHUTDOWN_HOOK_REGISTER,
        runtime -> {
          final Thread hook = new Thread(() -> {}, "probe-hook");
          runtime.decorateShutdownHook(hook);
          return "the hook";
        });

    // Queues
    INVOKERS.put(
        OperationType.QUEUE_PUT,
        runtime -> {
          runtime.beforeQueueOperation("QUEUE_PUT", new LinkedBlockingQueue<>());
          return "put";
        });
    INVOKERS.put(
        OperationType.QUEUE_TAKE,
        runtime -> {
          runtime.beforeQueueOperation("QUEUE_TAKE", new LinkedBlockingQueue<>());
          return "taken";
        });
    INVOKERS.put(
        OperationType.QUEUE_OFFER,
        runtime -> {
          // Bounded: a full queue is what a refused offer reports (an unbounded one is never full).
          final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(1);
          // A non-null answer skips the real offer and is its result (OnNonDefaultValue).
          final Boolean forced = runtime.beforeBooleanQueueOperation("QUEUE_OFFER", queue);
          final boolean offered = forced != null ? forced : queue.offer("element");
          return "offered " + offered + ", queue " + queue;
        });
    INVOKERS.put(
        OperationType.QUEUE_POLL,
        runtime -> {
          final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("element"));
          // A non-null answer skips the real poll, which then returns null.
          final Boolean forced = runtime.beforeBooleanQueueOperation("QUEUE_POLL", queue);
          return "polled " + (forced != null ? null : queue.poll());
        });

    // Futures
    INVOKERS.put(
        OperationType.ASYNC_COMPLETE,
        runtime -> {
          final CompletableFuture<Object> future = new CompletableFuture<>();
          final Boolean forced =
              runtime.beforeCompletableFutureComplete("ASYNC_COMPLETE", future, "payload");
          final boolean completed = forced != null ? forced : future.complete("payload");
          return "returned " + completed + ", future " + state(future);
        });
    INVOKERS.put(
        OperationType.ASYNC_COMPLETE_EXCEPTIONALLY,
        runtime -> {
          final CompletableFuture<Object> future = new CompletableFuture<>();
          final IllegalStateException payload = new IllegalStateException("payload");
          final Boolean forced =
              runtime.beforeCompletableFutureComplete(
                  "ASYNC_COMPLETE_EXCEPTIONALLY", future, payload);
          final boolean completed = forced != null ? forced : future.completeExceptionally(payload);
          return "returned " + completed + ", future " + state(future);
        });
    INVOKERS.put(
        OperationType.ASYNC_CANCEL,
        runtime -> {
          final CompletableFuture<Object> future = new CompletableFuture<>();
          // true short-circuits the real cancel.
          final boolean skipped = runtime.beforeAsyncCancel(future, false);
          final boolean cancelled = skipped || future.cancel(false);
          return "returned " + cancelled + ", future " + state(future);
        });

    // Class loading, methods, JVM runtime
    INVOKERS.put(
        OperationType.CLASS_LOAD,
        runtime -> {
          runtime.beforeClassLoad(loader(), "com.example.Probe");
          return "loaded";
        });
    INVOKERS.put(
        OperationType.CLASS_DEFINE,
        runtime -> {
          runtime.beforeClassDefine(loader(), "com.example.Probe");
          return "defined";
        });
    INVOKERS.put(
        OperationType.RESOURCE_LOAD,
        runtime ->
            String.valueOf(
                runtime.afterResourceLookup(
                    loader(), "probe.properties", URI.create("file:/probe.properties").toURL())));
    INVOKERS.put(
        OperationType.METHOD_ENTER,
        runtime -> {
          runtime.beforeMethodEnter(Probe.class, "value", "()Ljava/lang/String;");
          return "entered";
        });
    INVOKERS.put(
        OperationType.METHOD_EXIT,
        runtime ->
            String.valueOf(
                runtime.afterMethodExit(Probe.class, "value", "()Ljava/lang/String;", "value")));
    INVOKERS.put(OperationType.SYSTEM_GC_REQUEST, runtime -> "skip " + runtime.beforeGcRequest());
    INVOKERS.put(
        OperationType.SYSTEM_EXIT_REQUEST,
        runtime -> {
          runtime.beforeExitRequest(0);
          return "exiting";
        });
    INVOKERS.put(
        OperationType.REFLECTION_INVOKE,
        runtime -> {
          runtime.beforeReflectionInvoke(Object.class.getMethod("hashCode"), new Object());
          return "invoked";
        });
    INVOKERS.put(
        OperationType.DIRECT_BUFFER_ALLOCATE,
        runtime -> {
          runtime.beforeDirectBufferAllocate(LENGTH);
          return "allocated";
        });
    INVOKERS.put(
        OperationType.OBJECT_DESERIALIZE,
        runtime -> {
          runtime.beforeObjectDeserialize(null);
          return "read";
        });
    INVOKERS.put(
        OperationType.OBJECT_SERIALIZE,
        runtime -> {
          runtime.beforeObjectSerialize(null, "value");
          return "written";
        });
    INVOKERS.put(
        OperationType.NATIVE_LIBRARY_LOAD,
        runtime -> {
          runtime.beforeNativeLibraryLoad("probe");
          return "loaded";
        });
    INVOKERS.put(
        OperationType.JNDI_LOOKUP,
        runtime -> {
          runtime.beforeJndiLookup(null, "java:comp/env/probe");
          return "looked up";
        });
    INVOKERS.put(
        OperationType.JMX_INVOKE,
        runtime -> {
          runtime.beforeJmxInvoke(null, null, "probe");
          return "invoked";
        });
    INVOKERS.put(
        OperationType.JMX_GET_ATTR,
        runtime -> {
          runtime.beforeJmxGetAttr(null, null, "probe");
          return "read";
        });
    INVOKERS.put(
        OperationType.ZIP_INFLATE,
        runtime -> {
          runtime.beforeZipInflate();
          return "inflated";
        });
    INVOKERS.put(
        OperationType.ZIP_DEFLATE,
        runtime -> {
          runtime.beforeZipDeflate();
          return "deflated";
        });

    // Clock reads
    INVOKERS.put(
        OperationType.SYSTEM_CLOCK_MILLIS, runtime -> runtime.adjustClockMillis(1_000_000L));
    INVOKERS.put(OperationType.SYSTEM_CLOCK_NANOS, runtime -> runtime.adjustClockNanos(1_000_000L));
    INVOKERS.put(
        OperationType.INSTANT_NOW, runtime -> runtime.adjustInstantNow(Instant.ofEpochSecond(1L)));
    INVOKERS.put(
        OperationType.CLOCK_READ, runtime -> runtime.adjustClockInstant(Instant.ofEpochSecond(1L)));
    INVOKERS.put(
        OperationType.LOCAL_DATE_TIME_NOW,
        runtime ->
            runtime.adjustLocalDateTimeNowInZone(
                LocalDateTime.of(2026, 1, 1, 0, 0), ZoneOffset.UTC));
    INVOKERS.put(
        OperationType.ZONED_DATE_TIME_NOW,
        runtime ->
            runtime.adjustZonedDateTimeNow(
                ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)));
    INVOKERS.put(OperationType.DATE_NEW, runtime -> runtime.adjustDateNew(1_000_000L));

    // NIO
    INVOKERS.put(
        OperationType.NIO_SELECTOR_SELECT,
        runtime -> "wake up " + (runtime.beforeNioSelect(null, TIMEOUT_MILLIS) >= 0L));
    INVOKERS.put(OperationType.NIO_CHANNEL_READ, runtime -> channelOp(runtime, "NIO_CHANNEL_READ"));
    INVOKERS.put(
        OperationType.NIO_CHANNEL_CONNECT, runtime -> channelOp(runtime, "NIO_CHANNEL_CONNECT"));
    INVOKERS.put(
        OperationType.NIO_CHANNEL_ACCEPT, runtime -> channelOp(runtime, "NIO_CHANNEL_ACCEPT"));
    INVOKERS.put(
        OperationType.NIO_CHANNEL_WRITE,
        runtime -> "write " + runtime.beforeNioChannelWrite(null, LENGTH));

    // Sockets and datagrams
    INVOKERS.put(
        OperationType.SOCKET_CONNECT,
        runtime -> {
          final InetSocketAddress target =
              runtime.beforeSocketConnect(
                  null, new InetSocketAddress(LOOPBACK, PEER_PORT), TIMEOUT_MILLIS);
          return "connect to " + (target == null ? "the address asked for" : target);
        });
    INVOKERS.put(
        OperationType.SOCKET_ACCEPT,
        runtime -> {
          runtime.beforeSocketAccept(null);
          return "accepted";
        });
    INVOKERS.put(
        OperationType.SOCKET_READ,
        runtime ->
            read(
                runtime,
                runtime.beforeSocketRead(null, LENGTH, LOOPBACK, PEER_PORT, TIMEOUT_MILLIS)));
    INVOKERS.put(
        OperationType.SOCKET_WRITE,
        runtime -> "write " + runtime.beforeSocketWrite(null, LENGTH, LOOPBACK, PEER_PORT));
    INVOKERS.put(
        OperationType.SOCKET_CLOSE,
        runtime -> {
          try (Socket socket = new Socket()) {
            runtime.beforeSocketClose(socket);
            return "close with SO_LINGER " + socket.getSoLinger();
          }
        });
    INVOKERS.put(
        OperationType.SOCKET_BIND,
        runtime -> {
          runtime.beforeSocketBind(LOOPBACK, 0);
          return "bound";
        });
    INVOKERS.put(
        OperationType.SOCKET_SHUTDOWN,
        runtime -> {
          runtime.beforeSocketShutdown(null, LOOPBACK, PEER_PORT);
          return "shut down";
        });
    INVOKERS.put(
        OperationType.DATAGRAM_SEND,
        runtime -> {
          final int answer =
              runtime.beforeDatagram(
                  "DATAGRAM_SEND", null, new InetSocketAddress(LOOPBACK, PEER_PORT), LENGTH);
          return answer == ChaosDispatcher.DATAGRAM_DROPPED ? "dropped" : "send " + answer;
        });
    INVOKERS.put(
        OperationType.DATAGRAM_RECEIVE,
        runtime -> read(runtime, runtime.beforeDatagram("DATAGRAM_RECEIVE", null, null, LENGTH)));
    INVOKERS.put(
        OperationType.SSL_HANDSHAKE,
        runtime -> {
          runtime.beforeSslHandshake(null);
          return "handshaken";
        });

    // DNS
    INVOKERS.put(
        OperationType.DNS_RESOLVE, runtime -> "resolve " + runtime.beforeDnsResolve("probe.test"));
    INVOKERS.put(
        OperationType.DNS_ANSWER,
        runtime -> {
          final InetAddress[] answer = {
            InetAddress.getByAddress("probe.test", new byte[] {127, 0, 0, 1}),
            InetAddress.getByAddress("probe.test", new byte[] {127, 0, 0, 2})
          };
          // null keeps the resolver's answer.
          final InetAddress[] shaped = runtime.afterDnsAnswer("probe.test", answer, null);
          return Arrays.toString(shaped == null ? answer : shaped);
        });
    INVOKERS.put(
        OperationType.DNS_REVERSE,
        runtime -> {
          runtime.beforeDnsReverse("127.0.0.1");
          return "reversed";
        });

    // Files
    for (final OperationType file :
        List.of(
            OperationType.FILE_IO_READ,
            OperationType.FILE_IO_WRITE,
            OperationType.FILE_IO_SYNC,
            OperationType.FILE_IO_TRUNCATE,
            OperationType.FILE_IO_DELETE,
            OperationType.FILE_IO_MOVE,
            OperationType.FILE_IO_OPEN,
            OperationType.FILE_IO_MAP,
            OperationType.FILE_IO_CLOSE,
            OperationType.FILE_IO_MOVE_TARGET)) {
      INVOKERS.put(
          file,
          runtime -> {
            if (file == OperationType.FILE_IO_READ) {
              return read(
                  runtime, runtime.beforeFileIo("FILE_IO_READ", null, "/tmp/probe", LENGTH));
            }
            // A FileChannel subject, so a torn write can write short.
            return "count "
                + runtime.beforeFileIo(
                    file.name(), new ProbeFileChannelImpl(), "/tmp/probe", LENGTH);
          });
    }

    // HTTP and JDBC clients
    INVOKERS.put(
        OperationType.HTTP_CLIENT_SEND,
        runtime -> {
          runtime.beforeHttpSend("http://probe.test/", OperationType.HTTP_CLIENT_SEND);
          return "sent";
        });
    INVOKERS.put(
        OperationType.HTTP_CLIENT_SEND_ASYNC,
        runtime -> {
          runtime.beforeHttpSend("http://probe.test/", OperationType.HTTP_CLIENT_SEND_ASYNC);
          return "sent";
        });
    INVOKERS.put(
        OperationType.JDBC_CONNECTION_ACQUIRE,
        runtime -> {
          runtime.beforeJdbcConnectionAcquire("probe-pool");
          return "acquired";
        });
    INVOKERS.put(
        OperationType.JDBC_STATEMENT_EXECUTE,
        runtime -> {
          runtime.beforeJdbcStatementExecute("select 1");
          return "executed";
        });
    INVOKERS.put(
        OperationType.JDBC_PREPARED_STATEMENT,
        runtime -> {
          runtime.beforeJdbcPreparedStatement("select 1");
          return "prepared";
        });
    INVOKERS.put(
        OperationType.JDBC_TRANSACTION_COMMIT,
        runtime -> {
          runtime.beforeJdbcTransactionCommit();
          return "committed";
        });
    INVOKERS.put(
        OperationType.JDBC_TRANSACTION_ROLLBACK,
        runtime -> {
          runtime.beforeJdbcTransactionRollback();
          return "rolled back";
        });

    // Client libraries
    for (final OperationType client :
        List.of(
            OperationType.MESSAGE_SEND,
            OperationType.MESSAGE_RECEIVE,
            OperationType.RPC_CALL,
            OperationType.REDIS_COMMAND,
            OperationType.R2DBC_STATEMENT_EXECUTE,
            OperationType.AWS_SDK_CALL,
            OperationType.NETTY_CHANNEL_WRITE,
            OperationType.NETTY_CHANNEL_READ)) {
      INVOKERS.put(
          client,
          runtime -> {
            runtime.beforeClientCall(
                client.name(),
                new Object(),
                "probe",
                "probe",
                new InetSocketAddress(LOOPBACK, PEER_PORT));
            return "called";
          });
    }

    // Processes
    INVOKERS.put(
        OperationType.PROCESS_START,
        runtime -> {
          runtime.beforeProcessStart("probe");
          return "started";
        });
    INVOKERS.put(
        OperationType.PROCESS_WAIT,
        runtime -> "timed out " + runtime.beforeProcessWait(null, TIMEOUT_MILLIS));
  }

  /** One accepted (effect, operation) pair. */
  record Pair(
      String effectName, ChaosEffect effect, OperationType operation, ChaosSelector selector) {
    String name() {
      return effectName + " on " + operation;
    }

    ChaosScenario scenario() {
      return ChaosScenario.builder(SCENARIO_ID)
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(selector)
          .effect(effect)
          .activationPolicy(ActivationPolicy.always())
          .build();
    }

    @Override
    public String toString() {
      return name();
    }
  }

  /** What a call did: its result or failure, and how long it took. */
  private record Outcome(String result, long elapsedMillis) {
    static Outcome of(final ChaosRuntime runtime, final Invoker invoker) {
      final long start = System.nanoTime();
      String result;
      try {
        result = "returned " + invoker.invoke(runtime);
      } catch (final Throwable failure) {
        result = "threw " + failure.getClass().getName() + ": " + failure.getMessage();
      }
      return new Outcome(result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static List<Pair> acceptedPairs() {
    final List<Pair> pairs = new ArrayList<>();
    final Map<OperationType, List<ChaosSelector>> selectors = selectorsByOperation();
    int probe = 0;
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      for (final Map.Entry<OperationType, List<ChaosSelector>> operation : selectors.entrySet()) {
        for (final Map.Entry<String, ChaosEffect> effect : EFFECTS.entrySet()) {
          for (final ChaosSelector selector : operation.getValue()) {
            final Pair pair =
                new Pair(effect.getKey(), effect.getValue(), operation.getKey(), selector);
            final ChaosActivationHandle handle;
            try {
              handle =
                  runtime.activate(
                      ChaosScenario.builder("probe-" + probe++)
                          .scope(ChaosScenario.ScenarioScope.JVM)
                          .selector(selector)
                          .effect(effect.getValue())
                          .activationPolicy(ActivationPolicy.always())
                          .build());
            } catch (final RuntimeException rejected) {
              continue;
            }
            handle.stop();
            pairs.add(pair);
            break;
          }
        }
      }
    }
    return pairs;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("acceptedPairs")
  @DisplayName("the effect changes the call, or the call does not count as applied")
  void effectReachesTheCall(final Pair pair) {
    // Arrange
    final Invoker invoker = INVOKERS.get(pair.operation());
    assertThat(invoker).as("an invoker for %s", pair.operation()).isNotNull();
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final Outcome baseline = Outcome.of(runtime, invoker);
      runtime.activate(pair.scenario());

      // Act
      final Outcome chaos = Outcome.of(runtime, invoker);
      final ChaosDiagnostics.ScenarioReport report =
          runtime.diagnostics().scenario(SCENARIO_ID).orElseThrow();
      final long applied = report.appliedCount();

      // Assert
      final boolean waitedOutTheTimeout =
          pair.effect() instanceof ChaosEffect.TimeoutEffect
              && chaos.elapsedMillis() >= TIMEOUT_MILLIS;
      final boolean changed = !chaos.result().equals(baseline.result()) || waitedOutTheTimeout;
      final boolean dropped = applied > 0 && !changed;
      // A clock skew is applied on every read without counting a match; every other effect must
      // see the call, or the case would pass without checking anything.
      assertThat(report.matchedCount() > 0 || changed)
          .as("the call of %s reaches the scenario", pair.name())
          .isTrue();
      if (KNOWN_DROPPED.contains(pair.name())) {
        assertThat(dropped).as("%s is fixed: remove it from KNOWN_DROPPED", pair.name()).isTrue();
      } else {
        assertThat(dropped)
            .as(
                "%s counted %d application(s) but the call did not change: %s",
                pair.name(), applied, chaos.result())
            .isFalse();
      }
    }
  }

  @Test
  @DisplayName("the enumeration checks a sane number of pairs")
  void enumerationKeepsItsSize() {
    final List<Pair> pairs = acceptedPairs();
    // 420 when written; a shrinking enumeration must be noticed, not pass quietly.
    assertThat(pairs).hasSizeGreaterThan(350);
    assertThat(pairs.stream().map(Pair::name).collect(Collectors.toSet()))
        .as("every known dropped pair is still accepted")
        .containsAll(KNOWN_DROPPED);
  }

  @Test
  @DisplayName("a reject is refused on exactly the operations known to refuse it")
  void rejectRefusalsAreKnown() {
    final Set<OperationType> refused = EnumSet.noneOf(OperationType.class);
    refused.addAll(selectorsByOperation().keySet());
    acceptedPairs().stream()
        .filter(pair -> pair.effectName().equals("reject"))
        .forEach(pair -> refused.remove(pair.operation()));

    assertThat(refused).containsExactlyInAnyOrderElementsOf(KNOWN_REFUSED);
  }

  @Test
  @DisplayName("every operation has an invoker and every per-call effect is probed")
  void everyOperationAndEffectIsCovered() {
    final Set<OperationType> invocable = EnumSet.copyOf(INVOKERS.keySet());
    invocable.addAll(NOT_INVOCABLE.keySet());
    assertThat(invocable).isEqualTo(EnumSet.complementOf(EnumSet.of(OperationType.LIFECYCLE)));
    assertThat(selectorsByOperation().keySet())
        .as("every operation has a selector to probe it with")
        .isEqualTo(EnumSet.complementOf(EnumSet.of(OperationType.LIFECYCLE)));
    final Set<Class<?>> probed =
        EFFECTS.values().stream().map(Object::getClass).collect(Collectors.toSet());
    for (final Class<?> type : ChaosEffect.class.getPermittedSubclasses()) {
      if (type != ChaosEffect.DelayEffect.class
          && type != ChaosEffect.GateEffect.class
          && !CompatibilityValidator.STRESSOR_EFFECTS.contains(type)) {
        assertThat(probed).as("an instance of %s", type.getSimpleName()).contains(type);
      }
    }
  }

  // --- invoker helpers ---

  /** Submits a runnable; the advice submits whatever the dispatch returns in its place. */
  private static String submit(final ChaosRuntime runtime, final String operation) {
    final Runnable task = () -> {};
    final Runnable submitted = runtime.decorateExecutorRunnable(operation, new Object(), task);
    return submitted == task ? "the task" : "a replacement task";
  }

  private static String channelOp(final ChaosRuntime runtime, final String operation)
      throws Throwable {
    runtime.beforeNioChannelOp(operation, null);
    return "proceeded";
  }

  /**
   * Models the read advice: the peer delivers the bytes the dispatch allows; an encoded (negative)
   * count asks the advice to flip one bit of the bytes read, seeded by {@code corruptionSeed()}.
   */
  private static String read(final ChaosRuntime runtime, final int count) {
    final int delivered = count < 0 ? -count - 1 : count;
    final byte[] buffer = new byte[LENGTH];
    Arrays.fill(buffer, 0, Math.min(delivered, LENGTH), (byte) 0x55);
    if (count < 0 && delivered > 0) {
      final SplittableRandom random = new SplittableRandom(runtime.corruptionSeed());
      final int index = random.nextInt(delivered);
      buffer[index] = (byte) (buffer[index] ^ (1 << random.nextInt(8)));
    }
    return "read " + delivered + " bytes " + HexFormat.of().formatHex(buffer);
  }

  private static String state(final CompletableFuture<?> future) {
    if (future.isCancelled()) {
      return "cancelled";
    }
    if (future.isCompletedExceptionally()) {
      final Throwable failure = future.exceptionNow();
      return "failed with " + failure.getClass().getName() + ": " + failure.getMessage();
    }
    return future.isDone() ? "completed with " + future.getNow(null) : "pending";
  }

  private static ClassLoader loader() {
    return EffectReachesTheCallTest.class.getClassLoader();
  }

  /** The declaring class of the probed method; method selectors name it. */
  static final class Probe {}

  /**
   * A file I/O subject a torn write can tear: the dispatch recognises a {@code FileChannel} by the
   * {@code FileChannelImpl} suffix of its class name.
   */
  static final class ProbeFileChannelImpl {}

  // --- enumeration helpers ---

  /** For each operation, the selectors (with no filter) that select only that operation. */
  static Map<OperationType, List<ChaosSelector>> selectorsByOperation() {
    final Map<OperationType, List<ChaosSelector>> selectors = new EnumMap<>(OperationType.class);
    for (final Class<?> type : ChaosSelector.class.getPermittedSubclasses()) {
      for (final OperationType operation : OperationType.values()) {
        ChaosSelector selector = unfiltered(type, operation, false);
        if (selector == null) {
          // A method selector needs one pattern that is not ANY: it names the probe class.
          selector = unfiltered(type, operation, true);
        }
        if (selector != null) {
          selectors.computeIfAbsent(operation, any -> new ArrayList<>()).add(selector);
        }
      }
    }
    return selectors;
  }

  private static ChaosSelector unfiltered(
      final Class<?> type, final OperationType operation, final boolean nameProbe) {
    final RecordComponent[] components = type.getRecordComponents();
    if (components == null
        || Arrays.stream(components).noneMatch(c -> c.getName().equals("operations"))) {
      return null;
    }
    final Object[] arguments = new Object[components.length];
    final Class<?>[] parameterTypes = new Class<?>[components.length];
    boolean named = !nameProbe;
    for (int index = 0; index < components.length; index++) {
      parameterTypes[index] = components[index].getType();
      if (components[index].getName().equals("operations")) {
        arguments[index] = EnumSet.of(operation);
      } else if (parameterTypes[index] == NamePattern.class) {
        arguments[index] = named ? NamePattern.any() : NamePattern.exact(Probe.class.getName());
        named = true;
      } else {
        arguments[index] = defaultValue(parameterTypes[index]);
      }
    }
    try {
      final Constructor<?> canonical = type.getDeclaredConstructor(parameterTypes);
      canonical.setAccessible(true);
      return (ChaosSelector) canonical.newInstance(arguments);
    } catch (final InvocationTargetException rejected) {
      return null;
    } catch (final ReflectiveOperationException unreachable) {
      throw new IllegalStateException(type.getName(), unreachable);
    }
  }

  private static Object defaultValue(final Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
