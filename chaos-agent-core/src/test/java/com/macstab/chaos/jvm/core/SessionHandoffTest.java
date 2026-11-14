package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session hand-off keys carriers by identity, held weakly, and takes no lock of its own: equal
 * carriers keep their own sessions, a carrier's {@code hashCode} is never called, and a capture
 * whose carrier never ran cannot put every later run behind a JVM-wide monitor.
 */
@DisplayName("SessionHandoff")
class SessionHandoffTest {

  private final SessionHandoff handoff = new SessionHandoff();
  private final ScopeContext scopes = new ScopeContext();

  /** Equal to every other instance, as a value-typed task can be. */
  private static final class EqualTask implements Runnable {
    @Override
    public void run() {}

    @Override
    public boolean equals(final Object other) {
      return other instanceof EqualTask;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }

  /** A task whose hashCode throws, as a half-initialised value object's may. */
  private static final class HashlessTask implements Runnable {
    @Override
    public void run() {}

    @Override
    public boolean equals(final Object other) {
      throw new IllegalStateException("equals called");
    }

    @Override
    public int hashCode() {
      throw new IllegalStateException("hashCode called");
    }
  }

  @Test
  @DisplayName("two equal tasks handed off from two sessions run in their own sessions")
  void equalTasksKeepTheirSessions() {
    final EqualTask first = new EqualTask();
    final EqualTask second = new EqualTask();
    handoff.capture(first, "session-a");
    handoff.capture(second, "session-b");

    handoff.enter(first, scopes);
    assertThat(scopes.currentSessionId()).isEqualTo("session-a");
    handoff.exit(first);
    assertThat(scopes.currentSessionId()).isNull();

    handoff.enter(second, scopes);
    assertThat(scopes.currentSessionId()).isEqualTo("session-b");
    handoff.exit(second);
    assertThat(scopes.currentSessionId()).isNull();
  }

  @Test
  @DisplayName("a carrier whose hashCode throws is handed off and run without calling it")
  void throwingHashCodeIsNeverCalled() {
    final HashlessTask task = new HashlessTask();

    assertThatCode(() -> handoff.capture(task, "session-h")).doesNotThrowAnyException();
    handoff.enter(task, scopes);
    assertThat(scopes.currentSessionId()).isEqualTo("session-h");
    handoff.exit(task);
    assertThat(scopes.currentSessionId()).isNull();
  }

  @Test
  @DisplayName("a run without a capture, and a run on another thread's carrier, bind nothing")
  void nothingCapturedBindsNothing() {
    final Runnable captured = () -> {};
    handoff.capture(captured, "session-x");
    final Thread notStarted = new Thread(() -> {});
    handoff.capture(notStarted, "session-t");

    handoff.enter(new EqualTask(), scopes);
    handoff.enter(notStarted, scopes);
    assertThat(scopes.currentSessionId()).isNull();
    handoff.exit(new EqualTask());
  }

  @Test
  @DisplayName("takes no lock of its own: no synchronized code and no synchronized map")
  void takesNoLockOfItsOwn() throws Exception {
    for (final Method method : SessionHandoff.class.getDeclaredMethods()) {
      assertThat(Modifier.isSynchronized(method.getModifiers()))
          .as("synchronized method %s", method.getName())
          .isFalse();
    }
    final Class<?> synchronizedMap = Collections.synchronizedMap(Map.of()).getClass();
    for (final Field field : SessionHandoff.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      field.setAccessible(true);
      assertThat(field.get(handoff))
          .as("field %s", field.getName())
          .isNotInstanceOf(synchronizedMap);
    }
    final boolean[] monitor = {false};
    try (var classFile = SessionHandoff.class.getResourceAsStream("SessionHandoff.class")) {
      new ClassReader(classFile)
          .accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                  return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(final int opcode) {
                      if (opcode == Opcodes.MONITORENTER) {
                        monitor[0] = true;
                      }
                    }
                  };
                }
              },
              0);
    }
    assertThat(monitor[0]).as("a synchronized block").isFalse();
  }

  @Test
  @DisplayName("the capture of a carrier collected before it ran is dropped")
  void collectedCarrierIsDropped() throws Exception {
    handoff.capture(new Object(), "session-gone");
    final ConcurrentHashMap<?, ?> captured = capturedMap();
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!captured.isEmpty() && System.nanoTime() - deadline < 0) {
      System.gc();
      handoff.enter(new Object(), scopes);
      Thread.sleep(10);
    }

    assertThat(captured).as("dropped by a run that finds nothing").isEmpty();
  }

  private ConcurrentHashMap<?, ?> capturedMap() throws ReflectiveOperationException {
    final Field field = SessionHandoff.class.getDeclaredField("captured");
    field.setAccessible(true);
    return (ConcurrentHashMap<?, ?>) field.get(handoff);
  }
}
