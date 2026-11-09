package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.CompletableFuture;
import javax.management.ObjectName;
import org.junit.jupiter.api.DisplayName;
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
}
