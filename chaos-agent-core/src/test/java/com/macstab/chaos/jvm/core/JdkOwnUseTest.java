package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.ObjectName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JdkOwnUse (the L3a list)")
class JdkOwnUseTest {

  /** A class with every serialization hook and a look-alike of each. */
  @SuppressWarnings({"unused", "serial"})
  private static final class Hooks implements Serializable {
    private void writeObject(final ObjectOutputStream out) {}

    private void readObject(final ObjectInputStream in) {}

    private void readObjectNoData() {}

    private Object writeReplace() {
      return this;
    }

    private Object readResolve() {
      return this;
    }

    void writeObject(final String notAStream) {}

    String readResolve(final int notAHook) {
      return "";
    }
  }

  /** Methods with a hook's name and another descriptor: user methods, never skipped. */
  @SuppressWarnings({"unused", "serial"})
  private static final class LookAlikes implements Serializable {
    int writeObject(final ObjectOutputStream out) {
      return 0;
    }

    void writeObject(final ObjectOutputStream out, final int extra) {}

    int readObject(final ObjectInputStream in) {
      return 0;
    }

    void readObject(final ObjectInputStream in, final int extra) {}

    void readObject(final String notAStream) {}

    int readObjectNoData() {
      return 0;
    }

    String writeReplace() {
      return "";
    }

    Object writeReplace(final int notAHook) {
      return this;
    }
  }

  @Test
  @DisplayName("a method with a hook's name and another descriptor is not a serialization hook")
  void lookAlikesAreNotHooks() throws Exception {
    for (final java.lang.reflect.Method method : LookAlikes.class.getDeclaredMethods()) {
      if (!method.isSynthetic()) {
        assertThat(JdkOwnUse.isSerializationHook(method)).as(method.toString()).isFalse();
      }
    }
  }

  /** A static method with a hook's name and descriptor. */
  @SuppressWarnings("unused")
  private static final class StaticLookAlike {
    static Object writeReplace() {
      return "";
    }
  }

  @Test
  @DisplayName("serialization hooks are matched by name and descriptor")
  void serializationHooks() throws Exception {
    assertThat(
            JdkOwnUse.isSerializationHook(
                Hooks.class.getDeclaredMethod("writeObject", ObjectOutputStream.class)))
        .isTrue();
    assertThat(
            JdkOwnUse.isSerializationHook(
                Hooks.class.getDeclaredMethod("readObject", ObjectInputStream.class)))
        .isTrue();
    assertThat(JdkOwnUse.isSerializationHook(Hooks.class.getDeclaredMethod("readObjectNoData")))
        .isTrue();
    assertThat(JdkOwnUse.isSerializationHook(Hooks.class.getDeclaredMethod("writeReplace")))
        .isTrue();
    assertThat(JdkOwnUse.isSerializationHook(Hooks.class.getDeclaredMethod("readResolve")))
        .isTrue();
    assertThat(
            JdkOwnUse.isSerializationHook(
                Hooks.class.getDeclaredMethod("writeObject", String.class)))
        .isFalse();
    assertThat(
            JdkOwnUse.isSerializationHook(Hooks.class.getDeclaredMethod("readResolve", int.class)))
        .isFalse();
    assertThat(
            JdkOwnUse.isSerializationHook(StaticLookAlike.class.getDeclaredMethod("writeReplace")))
        .isFalse();
    assertThat(JdkOwnUse.isSerializationHook(Object.class.getMethod("hashCode"))).isFalse();
    assertThat(JdkOwnUse.isSerializationHook(Object.class.getConstructor())).isFalse();
    assertThat(JdkOwnUse.isSerializationHook(null)).isFalse();
  }

  /** A call made inside a channel's close. */
  @FunctionalInterface
  private interface CloseAction {
    boolean run() throws Throwable;
  }

  /** An interruptible channel that runs an action inside its close and keeps its result. */
  private static final class RecordingChannel
      extends java.nio.channels.spi.AbstractInterruptibleChannel {
    private final CloseAction action;
    final CompletableFuture<Boolean> result = new CompletableFuture<>();

    /** Counted down once {@link #begin()} has registered the interrupt hook. */
    final java.util.concurrent.CountDownLatch blocking = new java.util.concurrent.CountDownLatch(1);

    RecordingChannel(final CloseAction action) {
      this.action = action;
    }

    @Override
    protected void implCloseChannel() {
      try {
        result.complete(action.run());
      } catch (final Throwable failed) {
        result.completeExceptionally(failed);
      }
    }

    /** Blocks as an interruptible I/O operation does, until the channel is closed. */
    void block() {
      begin();
      blocking.countDown();
      try {
        result.get();
      } catch (final InterruptedException | java.util.concurrent.ExecutionException interrupted) {
        // the interrupt closed the channel
      } finally {
        try {
          end(true);
        } catch (final java.nio.channels.AsynchronousCloseException closed) {
          // ClosedByInterruptException: expected
        }
      }
    }
  }

  /** Runs {@code action} inside the close the JDK makes when it interrupts a blocked thread. */
  private static boolean insideInterrupt(final CloseAction action) throws Exception {
    return insideInterrupt(action, false);
  }

  /** As {@link #insideInterrupt(CloseAction)}, the blocked thread virtual when asked. */
  private static boolean insideInterrupt(final CloseAction action, final boolean virtual)
      throws Exception {
    final RecordingChannel channel = new RecordingChannel(action);
    final Thread reader =
        virtual ? Thread.ofVirtual().unstarted(channel::block) : new Thread(channel::block);
    reader.start();
    // Interrupted only once begin() registered the hook and the reader waits in result.get(): a
    // WAITING state before begin() (class loading on a loaded runner) is no blocked read.
    channel.blocking.await();
    while (reader.getState() != Thread.State.WAITING) {
      Thread.onSpinWait();
    }
    reader.interrupt();
    reader.join(5_000L);
    return channel.result.get(5, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("a channel close inside Thread.interrupt() is the JDK's, a close() call is not")
  void interruptClose() throws Exception {
    final RecordingChannel closedByCall = new RecordingChannel(JdkOwnUse::isInterruptClose);
    closedByCall.close();
    final CompletableFuture<Boolean> onThreadRun = new CompletableFuture<>();
    final Thread plain = new Thread(() -> onThreadRun.complete(JdkOwnUse.isInterruptClose()));
    plain.start();
    plain.join(5_000L);

    assertThat(insideInterrupt(JdkOwnUse::isInterruptClose)).isTrue();
    // VirtualThread.interrupt() overrides Thread.interrupt() without calling it.
    assertThat(insideInterrupt(JdkOwnUse::isInterruptClose, true)).as("virtual").isTrue();
    assertThat(closedByCall.result.getNow(null)).isFalse();
    assertThat(onThreadRun.getNow(null)).as("Thread.run on the stack").isFalse();
  }

  @Test
  @DisplayName("a FILE_IO_CLOSE or SOCKET_CLOSE delay skips the close an interrupt makes")
  void interruptCloseIsNotDelayed() throws Exception {
    try (ChaosRuntime runtime = new ChaosRuntime();
        ChaosActivationHandle file =
            runtime.activate(
                ChaosScenario.builder("file-close")
                    .selector(
                        ChaosSelector.fileIo(
                            Set.of(OperationType.FILE_IO_CLOSE, OperationType.FILE_IO_READ)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build());
        ChaosActivationHandle socket =
            runtime.activate(
                ChaosScenario.builder("socket-close")
                    .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CLOSE)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      final Object channel = new Object();
      insideInterrupt(
          () -> {
            runtime.beforeFileIo("FILE_IO_CLOSE", channel, "/data/f", -1);
            runtime.beforeSocketClose(channel);
            return true;
          });
      assertThat(file.appliedCount()).as("inside the interrupt").isZero();
      assertThat(socket.appliedCount()).as("inside the interrupt").isZero();

      runtime.beforeFileIo("FILE_IO_READ", channel, "/data/f", 1);
      runtime.beforeFileIo("FILE_IO_CLOSE", channel, "/data/f", -1);
      runtime.beforeSocketClose(channel);
      assertThat(file.appliedCount()).as("a read and a close() call").isEqualTo(2L);
      assertThat(socket.appliedCount()).as("a close() call").isEqualTo(1L);
    } catch (final Throwable failed) {
      throw new AssertionError(failed);
    }
  }

  @Test
  @DisplayName("the agent's MBean domain")
  void agentMBean() throws Exception {
    assertThat(JdkOwnUse.isAgentMBean(new ObjectName("com.macstab.chaos.jvm:type=X"))).isTrue();
    assertThat(JdkOwnUse.isAgentMBean(new ObjectName("java.lang:type=Runtime"))).isFalse();
    assertThat(JdkOwnUse.isAgentMBean("com.macstab.chaos.jvm:type=X")).isFalse();
    assertThat(JdkOwnUse.isAgentMBean(null)).isFalse();
  }

  @Test
  @DisplayName("JDK timers, JDK-internal futures and the random devices")
  void namedUses() {
    assertThat(JdkOwnUse.isCompletableFutureTimer(null)).isFalse();
    assertThat(JdkOwnUse.isCompletableFutureTimer((Runnable) () -> {})).isFalse();
    assertThat(JdkOwnUse.isJdkInternalFuture("jdk.internal.net.http.common.MinimalFuture"))
        .isTrue();
    assertThat(JdkOwnUse.isJdkInternalFuture(CompletableFuture.class.getName())).isFalse();
    assertThat(JdkOwnUse.isRandomDevice("/dev/urandom")).isTrue();
    assertThat(JdkOwnUse.isRandomDevice("/dev/urandom2")).isFalse();
    assertThat(JdkOwnUse.isRandomDevice(null)).isFalse();
    assertThat(JdkOwnUse.isJdkThreadLocal(null)).isFalse();
    assertThat(JdkOwnUse.isPoolWorkerLock(null)).isFalse();
    assertThat(JdkOwnUse.onHttpClientSelectorThread()).isFalse();
  }

  @Test
  @DisplayName("a fork-join task that is not a JDK timer task is not skipped")
  void forkJoinTasks() {
    assertThat(JdkOwnUse.isForkJoinTimerTask(null)).isFalse();
    assertThat(JdkOwnUse.isForkJoinTimerTask(new CompletableFuture<>())).isFalse();
  }

  @Test
  @DisplayName("a JDK class is one of a named module of the bootstrap or platform class loader")
  void jdkClasses() {
    assertThat(JdkOwnUse.isJdkClass(String.class)).as("bootstrap").isTrue();
    assertThat(JdkOwnUse.isJdkClass(java.sql.Connection.class)).as("platform").isTrue();
    assertThat(JdkOwnUse.isJdkClass(JdkOwnUseTest.class)).as("application").isFalse();
  }

  /** Reports whether the {@code Method.invoke} that called it came from a JDK class. */
  public static boolean reflectionCallerIsJdk() {
    return JdkOwnUse.isJdkReflectionCaller();
  }

  @Test
  @DisplayName("the application's own Method.invoke, or none on the stack, is no JDK caller")
  void applicationReflectionCaller() throws Exception {
    assertThat(JdkOwnUse.isJdkReflectionCaller()).as("no Method.invoke on the stack").isFalse();
    assertThat(JdkOwnUseTest.class.getMethod("reflectionCallerIsJdk").invoke(null))
        .as("called from this test class")
        .isEqualTo(false);
  }

  @Nested
  @DisplayName("the stack walk of a Method.invoke")
  class ReflectionCallerWalk {

    @Test
    @DisplayName("a REFLECTION_INVOKE scenario of another session makes no stack walk")
    void noWalkForACallNoScenarioSelects() throws Throwable {
      // Arrange
      final AtomicInteger walks = new AtomicInteger();
      try (ChaosRuntime runtime = new ChaosRuntime();
          ChaosSession other = runtime.openSession("other");
          ChaosSession mine = runtime.openSession("mine")) {
        other.activate(reflectionDelay(ChaosScenario.ScenarioScope.SESSION));
        final RuntimeDispatch dispatch = dispatch(runtime, walks, false);

        // Act: the call is made in another session
        try (ChaosSession.ScopeBinding ignored = mine.bind()) {
          dispatch.beforeReflectionInvoke(null, null);
        }
      }

      // Assert
      assertThat(walks).as("stack walks").hasValue(0);
    }

    @Test
    @DisplayName(
        "a REFLECTION_INVOKE scenario that selects the call walks once; a JDK caller skips it")
    void walkForACallAScenarioSelects() throws Throwable {
      // Arrange
      final AtomicInteger walks = new AtomicInteger();
      final long matched;
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(reflectionDelay(ChaosScenario.ScenarioScope.JVM));
        final RuntimeDispatch dispatch = dispatch(runtime, walks, true);

        // Act
        dispatch.beforeReflectionInvoke(null, null);
        matched = handle.matchedCount();
      }

      // Assert
      assertThat(walks).as("stack walks").hasValue(1);
      assertThat(matched).as("the JDK's own call is not counted").isZero();
    }

    private RuntimeDispatch dispatch(
        final ChaosRuntime runtime, final AtomicInteger walks, final boolean jdkCaller) {
      return new RuntimeDispatch(
          new DispatchEngine(runtime.controlPlane()),
          () -> {
            walks.incrementAndGet();
            return jdkCaller;
          });
    }

    private ChaosScenario reflectionDelay(final ChaosScenario.ScenarioScope scope) {
      return ChaosScenario.builder("reflection-" + scope)
          .scope(scope)
          .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)))
          .effect(ChaosEffect.delay(Duration.ofMillis(1)))
          .activationPolicy(ActivationPolicy.always())
          .build();
    }
  }
}
