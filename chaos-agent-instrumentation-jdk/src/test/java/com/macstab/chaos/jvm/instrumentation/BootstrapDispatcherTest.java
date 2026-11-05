package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BootstrapDispatcher")
class BootstrapDispatcherTest {

  private final Object executor = new Object();
  private final Object task = new Object();

  @AfterEach
  void resetBridge() {
    BootstrapDispatcher.install(null, null);
  }

  @Nested
  @DisplayName("when bridge is not installed")
  class WhenNotInstalled {

    @Test
    @DisplayName("decorateExecutorRunnable returns original task unchanged")
    void decorateExecutorRunnableReturnsFallback() throws Throwable {
      final Runnable original = () -> {};
      assertThat(
              BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, original))
          .isSameAs(original);
    }

    @Test
    @DisplayName("beforeScheduledTick returns true — task execution is allowed")
    void beforeScheduledTickReturnsTrueAsFallback() throws Throwable {
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isTrue();
    }

    @Test
    @DisplayName("beforeScheduledTick returns true for periodic tasks")
    void beforeScheduledTickReturnsTrueForPeriodic() throws Throwable {
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, true)).isTrue();
    }

    @Test
    @DisplayName("afterResourceLookup returns currentValue unchanged")
    void afterResourceLookupReturnsCurrentValue() throws Throwable {
      final URL url = URI.create("file:/test-resource").toURL();
      assertThat(BootstrapDispatcher.afterResourceLookup(null, "test-resource", url)).isSameAs(url);
    }

    @Test
    @DisplayName("afterResourceLookup with null currentValue returns null")
    void afterResourceLookupWithNullReturnNull() throws Throwable {
      assertThat(BootstrapDispatcher.afterResourceLookup(null, "missing", null)).isNull();
    }

    @Test
    @DisplayName("decorateShutdownHook returns original hook unchanged")
    void decorateShutdownHookReturnsFallback() throws Throwable {
      final Thread hook = new Thread(() -> {});
      assertThat(BootstrapDispatcher.decorateShutdownHook(hook)).isSameAs(hook);
    }

    @Test
    @DisplayName("resolveShutdownHook returns original thread unchanged")
    void resolveShutdownHookReturnsFallback() {
      final Thread original = new Thread(() -> {});
      assertThat(BootstrapDispatcher.resolveShutdownHook(original)).isSameAs(original);
    }

    @Test
    @DisplayName("beforeBooleanQueueOperation returns null")
    void beforeBooleanQueueOperationReturnsNull() throws Throwable {
      assertThat(BootstrapDispatcher.beforeBooleanQueueOperation("QUEUE_OFFER", new Object()))
          .isNull();
    }

    @Test
    @DisplayName("beforeCompletableFutureComplete returns null")
    void beforeCompletableFutureCompleteReturnsNull() throws Throwable {
      assertThat(BootstrapDispatcher.beforeCompletableFutureComplete("ASYNC_COMPLETE", null, null))
          .isNull();
    }

    @Test
    @DisplayName("void dispatch methods complete without throwing")
    void voidDispatchMethodsCompleteWithoutThrowing() {
      final Thread thread = new Thread(() -> {});
      assertThatCode(() -> BootstrapDispatcher.beforeThreadStart(thread))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeWorkerRun(executor, thread, () -> {}))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", new Object()))
          .doesNotThrowAnyException();
      assertThatCode(
              () -> BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 0L))
          .doesNotThrowAnyException();
      assertThatCode(() -> BootstrapDispatcher.beforeClassLoad(null, "com.example.Foo"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adjustInstantNow returns real instant unchanged")
    void adjustInstantNowReturnsFallback() throws Throwable {
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L);
      assertThat(BootstrapDispatcher.adjustInstantNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustLocalDateTimeNow returns real value unchanged")
    void adjustLocalDateTimeNowReturnsFallback() throws Throwable {
      final java.time.LocalDateTime real = java.time.LocalDateTime.of(2026, 4, 18, 12, 0);
      assertThat(BootstrapDispatcher.adjustLocalDateTimeNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustZonedDateTimeNow returns real value unchanged")
    void adjustZonedDateTimeNowReturnsFallback() throws Throwable {
      final java.time.ZonedDateTime real =
          java.time.ZonedDateTime.of(2026, 4, 18, 12, 0, 0, 0, java.time.ZoneId.of("UTC"));
      assertThat(BootstrapDispatcher.adjustZonedDateTimeNow(real)).isSameAs(real);
    }

    @Test
    @DisplayName("adjustDateNew returns real millis unchanged")
    void adjustDateNewReturnsFallback() throws Throwable {
      assertThat(BootstrapDispatcher.adjustDateNew(1_700_000_000_000L))
          .isEqualTo(1_700_000_000_000L);
    }
  }

  @Nested
  @DisplayName("when bridge is installed")
  class WhenInstalled {

    @Test
    @DisplayName("beforeScheduledTick forwards call to bridge and returns bridge result")
    void beforeScheduledTickForwardsToBridge() throws Throwable {
      final AtomicInteger calls = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            assertThat(e).isSameAs(executor);
            assertThat(t).isSameAs(task);
            assertThat(p).isTrue();
            calls.incrementAndGet();
            return false;
          });

      final boolean result = BootstrapDispatcher.beforeScheduledTick(executor, task, true);

      assertThat(result).isFalse();
      assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("decorateExecutorRunnable forwards call to bridge and returns decorated task")
    void decorateExecutorRunnableForwardsToBridge() throws Throwable {
      final Runnable original = () -> {};
      final Runnable decorated = () -> {};
      installDecorateBridge(
          (op, ex, t) -> {
            assertThat(op).isEqualTo("EXECUTOR_SUBMIT");
            assertThat(ex).isSameAs(executor);
            assertThat(t).isSameAs(original);
            return decorated;
          });

      final Runnable result =
          BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", executor, original);

      assertThat(result).isSameAs(decorated);
    }

    @Test
    @DisplayName("reinstalling bridge with null uninstalls — fallback behaviour resumes")
    void reinstallWithNullRestoresFallback() throws Throwable {
      installTickBridge((e, t, p) -> false);
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isFalse();

      BootstrapDispatcher.install(null, null);
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isTrue();
    }
  }

  @Nested
  @DisplayName("reentrancy guard")
  class ReentrancyGuard {

    @Test
    @DisplayName(
        "nested dispatch during bridge execution returns fallback without re-entering bridge")
    void nestedCallReturnsFallbackWithoutReenteringBridge() throws Throwable {
      final AtomicInteger bridgeCallCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            bridgeCallCount.incrementAndGet();
            // Nested call: DEPTH=1, guard fires immediately, returns fallback true
            final boolean nested = BootstrapDispatcher.beforeScheduledTick(e, t, p);
            assertThat(nested).as("nested call must return fallback (true)").isTrue();
            return false;
          });

      final boolean result = BootstrapDispatcher.beforeScheduledTick(executor, task, false);

      assertThat(result).isFalse();
      assertThat(bridgeCallCount.get())
          .as("bridge must be entered exactly once despite nested call")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("depth is reset after normal call — subsequent call dispatches to bridge")
    void depthResetAfterNormalCall() throws Throwable {
      installTickBridge((e, t, p) -> false);
      BootstrapDispatcher.beforeScheduledTick(executor, task, false);

      // Replace bridge and call again — must dispatch, not return fallback
      final AtomicInteger secondCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            secondCount.incrementAndGet();
            return true;
          });

      final boolean second = BootstrapDispatcher.beforeScheduledTick(executor, task, false);
      assertThat(second).isTrue();
      assertThat(secondCount.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("exception propagation")
  class ExceptionPropagation {

    @Test
    @DisplayName("RuntimeException from bridge propagates as original type without wrapping")
    void runtimeExceptionPropagatesUnwrapped() throws Exception {
      final RuntimeException injected = new RuntimeException("bridge-failure");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("checked exception from bridge propagates via sneaky throw as original type")
    void checkedExceptionPropagatesSneakily() throws Exception {
      final Exception injected = new Exception("checked-bridge-failure");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Error from bridge propagates as original type without wrapping")
    void errorPropagatesUnwrapped() throws Exception {
      final Error injected = new AssertionError("bridge-error");
      installTickBridge(
          (e, t, p) -> {
            throw injected;
          });

      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(injected)
          .isExactlyInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("depth is reset after exception — subsequent call dispatches normally")
    void depthResetAfterException() throws Throwable {
      final RuntimeException ex = new RuntimeException("fail");
      installTickBridge(
          (e, t, p) -> {
            throw ex;
          });
      assertThatThrownBy(() -> BootstrapDispatcher.beforeScheduledTick(executor, task, false))
          .isSameAs(ex);

      // DEPTH must have been reset in the finally block — next call must enter bridge
      final AtomicInteger recoveryCount = new AtomicInteger(0);
      installTickBridge(
          (e, t, p) -> {
            recoveryCount.incrementAndGet();
            return false;
          });
      final boolean recovered = BootstrapDispatcher.beforeScheduledTick(executor, task, false);
      assertThat(recovered).isFalse();
      assertThat(recoveryCount.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("void dispatch with bridge installed")
  class VoidDispatchWithBridgeInstalled {

    @Test
    @DisplayName("beforeThreadStart forwards call to bridge")
    void beforeThreadStartForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final Thread thread = new Thread(() -> {});
      installThreadStartBridge(
          t -> {
            assertThat(t).isSameAs(thread);
            called.set(true);
          });

      BootstrapDispatcher.beforeThreadStart(thread);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeWorkerRun forwards call to bridge")
    void beforeWorkerRunForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final Thread worker = new Thread(() -> {});
      final Runnable task = () -> {};
      installWorkerRunBridge(
          (e, w, t) -> {
            assertThat(e).isSameAs(executor);
            assertThat(w).isSameAs(worker);
            assertThat(t).isSameAs(task);
            called.set(true);
          });

      BootstrapDispatcher.beforeWorkerRun(executor, worker, task);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeForkJoinTaskRun forwards call to bridge")
    void beforeForkJoinTaskRunForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      final ForkJoinTask<?> fjTask = ForkJoinTask.adapt(() -> {});
      installForkJoinTaskBridge(
          t -> {
            assertThat(t).isSameAs(fjTask);
            called.set(true);
          });

      BootstrapDispatcher.beforeForkJoinTaskRun(fjTask);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeQueueOperation forwards call to bridge")
    void beforeQueueOperationForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installQueueOperationBridge(
          (op, q) -> {
            assertThat(op).isEqualTo("QUEUE_PUT");
            assertThat(q).isSameAs(task);
            called.set(true);
          });

      BootstrapDispatcher.beforeQueueOperation("QUEUE_PUT", task);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeExecutorShutdown forwards call to bridge")
    void beforeExecutorShutdownForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installExecutorShutdownBridge(
          (op, e, t) -> {
            assertThat(op).isEqualTo("EXECUTOR_SHUTDOWN");
            assertThat(e).isSameAs(executor);
            assertThat(t).isEqualTo(1000L);
            called.set(true);
          });

      BootstrapDispatcher.beforeExecutorShutdown("EXECUTOR_SHUTDOWN", executor, 1000L);

      assertThat(called).isTrue();
    }

    @Test
    @DisplayName("beforeClassLoad forwards call to bridge")
    void beforeClassLoadForwardsToBridge() throws Throwable {
      final AtomicBoolean called = new AtomicBoolean(false);
      installClassLoadBridge(
          (loader, name) -> {
            assertThat(loader).isNull();
            assertThat(name).isEqualTo("com.example.Foo");
            called.set(true);
          });

      BootstrapDispatcher.beforeClassLoad(null, "com.example.Foo");

      assertThat(called).isTrue();
    }
  }

  @Nested
  @DisplayName("a delegate's answer")
  class DelegateAnswer {

    @Test
    @DisplayName("a delegate's true reaches the advice of every yes/no hook")
    void trueReachesTheAdvice() throws Throwable {
      final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
      for (final int slot :
          new int[] {
            BootstrapDispatcher.BEFORE_GC_REQUEST,
            BootstrapDispatcher.BEFORE_ASYNC_CANCEL,
            BootstrapDispatcher.BEFORE_THREAD_LOCAL_GET,
            BootstrapDispatcher.BEFORE_THREAD_LOCAL_SET,
            BootstrapDispatcher.BEFORE_HTTP_SEND_ASYNC
          }) {
        handles[slot] = answering(slot, true);
      }
      BootstrapDispatcher.install(new Object(), handles);

      assertThat(BootstrapDispatcher.beforeGcRequest()).isTrue();
      assertThat(BootstrapDispatcher.beforeAsyncCancel(new Object(), true)).isTrue();
      assertThat(BootstrapDispatcher.beforeThreadLocalGet(new ThreadLocal<>())).isTrue();
      assertThat(BootstrapDispatcher.beforeThreadLocalSet(new ThreadLocal<>(), "v")).isTrue();
      assertThat(BootstrapDispatcher.beforeHttpSendAsync("https://api.example.com/")).isTrue();
    }

    @Test
    @DisplayName("the session header goes to an internal host only")
    void sessionHeaderForInternalHostsOnly() throws Exception {
      final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
      handles[BootstrapDispatcher.CURRENT_SESSION_ID] =
          answering(BootstrapDispatcher.CURRENT_SESSION_ID, "session-1");
      BootstrapDispatcher.install(new Object(), handles);

      assertThat(BootstrapDispatcher.sessionHeaderFor("orders")).isEqualTo("session-1");
      assertThat(BootstrapDispatcher.sessionHeaderFor("api.stripe.com")).isNull();
    }

    @Test
    @DisplayName("a dropped datagram is reported sent, with or without its buffer")
    void droppedDatagram() throws Throwable {
      final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
      handles[BootstrapDispatcher.BEFORE_DATAGRAM] =
          answering(BootstrapDispatcher.BEFORE_DATAGRAM, Integer.MIN_VALUE);
      BootstrapDispatcher.install(new Object(), handles);

      assertThat(BootstrapDispatcher.beforeDatagramSend(executor, null, null)).isEqualTo(1);
      assertThat(BootstrapDispatcher.dropDatagramPacket(executor, null)).isTrue();
      assertThat(
              BootstrapDispatcher.dropDatagramPacket(
                  executor, new java.net.DatagramPacket(new byte[4], 4)))
          .as("a packet without an address, sent to the connected peer")
          .isTrue();
      assertThat(BootstrapDispatcher.corruptDatagramPacket(executor, null)).isTrue();
    }

    /** A handle of the delegate's shape for {@code slot} that returns {@code answer}. */
    private MethodHandle answering(final int slot, final Object answer) throws Exception {
      final MethodType type =
          JdkInstrumentationInstaller.buildMethodHandles()[slot]
              .type()
              .changeParameterType(0, Object.class);
      return MethodHandles.dropArguments(
          MethodHandles.constant(type.returnType(), answer), 0, type.parameterList());
    }
  }

  @Nested
  @DisplayName("scopes and internal calls")
  class Scopes {

    @Test
    @DisplayName("leaving a scope that was not entered leaves it at its outermost level")
    void unbalancedExitScope() {
      assertThat(BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_CLOSE)).isTrue();
      assertThat(BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_FILE_CLOSE)).isTrue();
      assertThat(BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_FILE_CLOSE)).isTrue();
    }

    @Test
    @DisplayName("ending an internal call that was not begun keeps dispatching to the bridge")
    void unbalancedEndInternalCall() throws Throwable {
      final AtomicInteger calls = new AtomicInteger();
      installTickBridge(
          (e, t, p) -> {
            calls.incrementAndGet();
            return false;
          });
      BootstrapDispatcher.endInternalCall();
      assertThat(BootstrapDispatcher.beforeScheduledTick(executor, task, false)).isFalse();
      assertThat(calls).hasValue(1);
    }
  }

  @Nested
  @DisplayName("helpers the advice calls")
  class AdviceHelpers {

    @Test
    @DisplayName("the header filter of a copied request keeps every header")
    void allHeadersKeepsEveryHeader() {
      final java.net.http.HttpRequest original =
          java.net.http.HttpRequest.newBuilder(URI.create("http://orders/"))
              .header("Authorization", "Bearer t")
              .header("X-Trace", "1")
              .build();
      final java.net.http.HttpRequest copy =
          java.net.http.HttpRequest.newBuilder(original, BootstrapDispatcher.ALL_HEADERS).build();
      assertThat(copy.headers().map()).isEqualTo(original.headers().map());
    }

    @Test
    @DisplayName("archives are .jar, .zip and .jmod files")
    void archives() {
      assertThat(BootstrapDispatcher.isArchive("/lib/app.jar")).isTrue();
      assertThat(BootstrapDispatcher.isArchive("/lib/data.zip")).isTrue();
      assertThat(BootstrapDispatcher.isArchive("/jmods/java.base.jmod")).isTrue();
      assertThat(BootstrapDispatcher.isArchive("/data/orders.csv")).isFalse();
      assertThat(BootstrapDispatcher.isArchive(null)).isFalse();
    }

    @Test
    @DisplayName("a corrupted single-byte read flips one bit, end of stream stays")
    void corruptByte() {
      assertThat(BootstrapDispatcher.corruptByte(-1)).isEqualTo(-1);
      assertThat(Integer.bitCount(BootstrapDispatcher.corruptByte(0))).isEqualTo(1);
      BootstrapDispatcher.beginInternalCall();
      try {
        assertThat(Integer.bitCount(BootstrapDispatcher.corruptByte(0)))
            .as("seeded without the bridge inside agent work")
            .isEqualTo(1);
      } finally {
        BootstrapDispatcher.endInternalCall();
      }
      BootstrapDispatcher.install(new Object(), null);
      assertThat(Integer.bitCount(BootstrapDispatcher.corruptByte(0)))
          .as("seeded without the bridge before its handles are published")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("a corrupted array read flips one bit of the bytes read, nothing without bytes")
    void corruptBytes() {
      final byte[] buffer = new byte[8];
      BootstrapDispatcher.corruptBytes(null, 0, 4);
      BootstrapDispatcher.corruptBytes(buffer, 0, 0);
      assertThat(bitsSet(ByteBuffer.wrap(buffer))).isZero();
      BootstrapDispatcher.corruptBytes(buffer, 2, 3);
      assertThat(bitsSet(ByteBuffer.wrap(buffer))).isEqualTo(1);
      assertThat(buffer[0]).isZero();
      assertThat(buffer[1]).isZero();
      assertThat(buffer[5]).isZero();
    }

    @Test
    @DisplayName("a corrupted channel read flips one bit of the bytes before the position")
    void corruptBuffer() {
      final ByteBuffer buffer = ByteBuffer.allocate(8);
      buffer.position(6);
      BootstrapDispatcher.corruptBuffer(buffer, 3);
      assertThat(bitsSet(buffer)).isEqualTo(1);
      for (final int untouched : new int[] {0, 1, 2, 6, 7}) {
        assertThat(buffer.get(untouched)).as("byte %d", untouched).isZero();
      }
    }

    @Test
    @DisplayName("a channel read without bytes, read-only or shorter than claimed is not corrupted")
    void corruptBufferRefusals() {
      final ByteBuffer buffer = ByteBuffer.allocate(8);
      buffer.position(2);
      BootstrapDispatcher.corruptBuffer(null, 1);
      BootstrapDispatcher.corruptBuffer(buffer, 0);
      BootstrapDispatcher.corruptBuffer(buffer, 3);
      final ByteBuffer readOnly = ByteBuffer.allocate(8).asReadOnlyBuffer();
      readOnly.position(4);
      BootstrapDispatcher.corruptBuffer(readOnly, 4);
      assertThat(bitsSet(buffer)).isZero();
      assertThat(bitsSet(readOnly)).isZero();
    }

    @Test
    @DisplayName("calls without a buffer or packet pass through")
    void missingBuffers() throws Throwable {
      assertThat(BootstrapDispatcher.beforeSocketChannelWrite(executor, (ByteBuffer) null))
          .isNull();
      assertThat(BootstrapDispatcher.beforeDatagramSend(executor, null, null)).isZero();
      assertThat(BootstrapDispatcher.dropDatagramPacket(executor, null)).isFalse();
      assertThat(BootstrapDispatcher.corruptDatagramPacket(executor, null)).isFalse();
    }

    private int bitsSet(final ByteBuffer buffer) {
      int bits = 0;
      for (int i = 0; i < buffer.capacity(); i++) {
        bits += Integer.bitCount(buffer.get(i) & 0xFF);
      }
      return bits;
    }
  }

  // — bridge test infrastructure —

  @FunctionalInterface
  interface TickBehavior {
    boolean tick(Object executor, Object task, boolean periodic) throws Throwable;
  }

  @Nested
  @DisplayName("dispatch allocation")
  class DispatchAllocation {

    private static final int CALLS = 10_000;

    @Test
    @DisplayName("a dispatch that changes nothing allocates no per-call objects")
    void dispatchDoesNotAllocatePerCall() throws Throwable {
      final Runnable task = () -> {};
      BootstrapDispatcher.install(null, null);
      allocatedBytesOver(task);
      final long notInstalled = allocatedBytesOver(task);
      installDecorateBridge((operation, executor, runnable) -> runnable);
      // The first round warms up the method-handle adaptation, which allocates once.
      allocatedBytesOver(task);
      final long installed = allocatedBytesOver(task);

      // A capturing lambda per call costs at least 24 bytes, i.e. 240 KB over 10,000 calls; the
      // bound leaves room for the measurement's own noise but not for any per-call object.
      assertThat(notInstalled).as("bytes allocated with no delegate").isLessThan(CALLS);
      assertThat(installed).as("bytes allocated with a pass-through delegate").isLessThan(CALLS);
    }

    private long allocatedBytesOver(final Runnable task) throws Throwable {
      final com.sun.management.ThreadMXBean threads =
          (com.sun.management.ThreadMXBean)
              java.lang.management.ManagementFactory.getThreadMXBean();
      final long threadId = Thread.currentThread().threadId();
      final long before = threads.getThreadAllocatedBytes(threadId);
      for (int i = 0; i < CALLS; i++) {
        BootstrapDispatcher.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task);
      }
      return threads.getThreadAllocatedBytes(threadId) - before;
    }
  }

  @FunctionalInterface
  interface DecorateBehavior {
    Runnable decorate(String operation, Object executor, Runnable task) throws Throwable;
  }

  /**
   * Installs a minimal bridge with only {@code BEFORE_SCHEDULED_TICK} wired. All other slots are
   * left null; they will NPE if accessed, which is intentional — tests that trigger unrelated slots
   * have a design problem.
   */
  private static void installTickBridge(final TickBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_SCHEDULED_TICK] =
        MethodHandles.lookup()
            .findVirtual(
                TickBehavior.class,
                "tick",
                MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  /** Installs a minimal bridge with only {@code DECORATE_EXECUTOR_RUNNABLE} wired. */
  private static void installDecorateBridge(final DecorateBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.DECORATE_EXECUTOR_RUNNABLE] =
        MethodHandles.lookup()
            .findVirtual(
                DecorateBehavior.class,
                "decorate",
                MethodType.methodType(Runnable.class, String.class, Object.class, Runnable.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  @FunctionalInterface
  interface ThreadStartBehavior {
    void act(Thread thread) throws Throwable;
  }

  @FunctionalInterface
  interface WorkerRunBehavior {
    void act(Object executor, Thread worker, Runnable task) throws Throwable;
  }

  @FunctionalInterface
  interface ForkJoinTaskBehavior {
    void act(ForkJoinTask<?> task) throws Throwable;
  }

  @FunctionalInterface
  interface QueueOperationBehavior {
    void act(String operation, Object queue) throws Throwable;
  }

  @FunctionalInterface
  interface ExecutorShutdownBehavior {
    void act(String operation, Object executor, long timeoutMillis) throws Throwable;
  }

  @FunctionalInterface
  interface ClassLoadBehavior {
    void act(ClassLoader loader, String className) throws Throwable;
  }

  private static void installThreadStartBridge(final ThreadStartBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    // The dispatcher passes (thread, poolWorker); the behaviour only looks at the thread.
    handles[BootstrapDispatcher.BEFORE_THREAD_START] =
        MethodHandles.dropArguments(
            MethodHandles.lookup()
                .findVirtual(
                    ThreadStartBehavior.class,
                    "act",
                    MethodType.methodType(void.class, Thread.class)),
            2,
            boolean.class);
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installWorkerRunBridge(final WorkerRunBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_WORKER_RUN] =
        MethodHandles.lookup()
            .findVirtual(
                WorkerRunBehavior.class,
                "act",
                MethodType.methodType(void.class, Object.class, Thread.class, Runnable.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installForkJoinTaskBridge(final ForkJoinTaskBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_FORK_JOIN_TASK_RUN] =
        MethodHandles.lookup()
            .findVirtual(
                ForkJoinTaskBehavior.class,
                "act",
                MethodType.methodType(void.class, ForkJoinTask.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installQueueOperationBridge(final QueueOperationBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_QUEUE_OPERATION] =
        MethodHandles.lookup()
            .findVirtual(
                QueueOperationBehavior.class,
                "act",
                MethodType.methodType(void.class, String.class, Object.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installExecutorShutdownBridge(final ExecutorShutdownBehavior behavior)
      throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_EXECUTOR_SHUTDOWN] =
        MethodHandles.lookup()
            .findVirtual(
                ExecutorShutdownBehavior.class,
                "act",
                MethodType.methodType(void.class, String.class, Object.class, long.class));
    BootstrapDispatcher.install(behavior, handles);
  }

  private static void installClassLoadBridge(final ClassLoadBehavior behavior) throws Exception {
    final MethodHandle[] handles = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    handles[BootstrapDispatcher.BEFORE_CLASS_LOAD] =
        MethodHandles.lookup()
            .findVirtual(
                ClassLoadBehavior.class,
                "act",
                MethodType.methodType(void.class, ClassLoader.class, String.class));
    BootstrapDispatcher.install(behavior, handles);
  }
}
