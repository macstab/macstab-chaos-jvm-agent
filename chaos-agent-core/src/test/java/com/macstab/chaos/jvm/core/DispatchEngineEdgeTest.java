package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** How the dispatch engine turns a decision into what the intercepted call does. */
@DisplayName("DispatchEngine edge behaviour")
class DispatchEngineEdgeTest {

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final DispatchEngine engine = new DispatchEngine(runtime.controlPlane());

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  private static RuntimeDecision terminal(final TerminalAction action) {
    return new RuntimeDecision(0L, null, action);
  }

  private static ChaosScenario scenario(final ChaosEffect effect) {
    return ChaosScenario.builder("edge")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
        .effect(effect)
        .build();
  }

  /** A Throwable whose only constructor sets its own cause, so a later initCause is refused. */
  public static final class PreCausedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PreCausedException() {
      super(new IllegalArgumentException("own cause"));
    }
  }

  @Nested
  @DisplayName("transferCount")
  class ReadCount {

    @Test
    @DisplayName("a terminal that does not shape reads leaves the requested count")
    void otherTerminalLeavesCount() throws Throwable {
      assertThat(engine.transferCount(terminal(new TerminalAction.DnsRewrite("elsewhere")), 64))
          .isEqualTo(64);
    }

    @Test
    @DisplayName("a length cap leaves a read of no bytes as it is")
    void capKeepsEmptyRead() throws Throwable {
      assertThat(engine.transferCount(terminal(new TerminalAction.CapLength(4L)), 0)).isZero();
    }
  }

  @Nested
  @DisplayName("terminal actions")
  class Terminals {

    @Test
    @DisplayName("a trickle caps a read of unknown size")
    void trickleCapsUnknownSize() {
      final InvocationContext unknownSize =
          new InvocationContext(
              OperationType.SOCKET_READ, "java.net.Socket", null, null, false, null, null, null);
      final ChaosEffect.TrickleEffect trickle = new ChaosEffect.TrickleEffect(8);
      final TerminalAction action =
          engine.terminalActionFor(unknownSize, trickle, scenario(trickle), null, 1L);
      assertThat(action)
          .isInstanceOfSatisfying(
              TerminalAction.CapLength.class, cap -> assertThat(cap.limit()).isEqualTo(8L));
    }

    @Test
    @DisplayName("an errno on a call without an owner class fails the java.nio way")
    void errnoWithoutOwnerClass() {
      final InvocationContext noOwner =
          new InvocationContext(
              OperationType.FILE_IO_OPEN,
              null,
              null,
              null,
              false,
              null,
              null,
              null,
              InvocationContext.UNKNOWN,
              "/data/file",
              null,
              InvocationContext.UNKNOWN,
              null,
              InvocationContext.UNKNOWN);
      final ChaosEffect.ErrnoEffect errno = ChaosEffect.errno(ChaosEffect.Errno.ENOENT);
      final TerminalAction action =
          engine.terminalActionFor(noOwner, errno, scenario(errno), null, 1L);
      assertThat(action)
          .isInstanceOfSatisfying(
              TerminalAction.Throw.class,
              thrown ->
                  assertThat(thrown.failure())
                      .isInstanceOf(java.nio.file.NoSuchFileException.class)
                      .hasMessage("/data/file"));
    }

    @Test
    @DisplayName("an injected exception that sets its own cause keeps it")
    void injectedExceptionKeepsOwnCause() {
      final TerminalAction.Throw action =
          engine.buildInjectedExceptionTerminal(
              new ChaosEffect.ExceptionInjectionEffect(
                  PreCausedException.class.getName(), "configured message", true));
      assertThat(action.failure())
          .isInstanceOf(PreCausedException.class)
          .cause()
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("own cause");
    }
  }

  @Nested
  @DisplayName("applyPreDecision")
  class PreDecision {

    @Test
    @DisplayName("a RETURN carrying a value other than the suppression marker lets the call go on")
    void returnWithValueProceeds() {
      assertThatCode(
              () -> engine.applyPreDecision(terminal(new TerminalAction.Return(Boolean.TRUE))))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a RETURN without a value lets the call go on")
    void returnWithoutValueProceeds() {
      assertThatCode(() -> engine.applyPreDecision(terminal(new TerminalAction.Return(null))))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a corrupt-return terminal before the call is refused as a misconfiguration")
    void corruptReturnRefused() {
      assertThatThrownBy(
              () ->
                  engine.applyPreDecision(
                      terminal(
                          new TerminalAction.CorruptReturn(
                              ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL),
                              "corrupt"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "CORRUPT_RETURN terminal reached applyPreDecision — only valid from afterMethodExit");
    }

    @Test
    @DisplayName("a terminal with no pre-call meaning lets the call go on")
    void otherTerminalProceeds() {
      assertThatCode(() -> engine.applyPreDecision(terminal(new TerminalAction.Suppress())))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("propagate")
  class Propagate {

    @Test
    @DisplayName("a checked exception is wrapped without touching the interrupt flag")
    void wrapsCheckedException() {
      final IOException checked = new IOException("disk");
      final RuntimeException wrapped = engine.propagate(checked);
      assertThat(wrapped)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("chaos interception failed")
          .hasCause(checked);
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("an InterruptedException is wrapped and the interrupt flag restored")
    void wrapsInterruptAndRestoresFlag() {
      final InterruptedException interrupted = new InterruptedException("stop");
      final RuntimeException wrapped = engine.propagate(interrupted);
      assertThat(Thread.interrupted()).isTrue();
      assertThat(wrapped).isInstanceOf(IllegalStateException.class).hasCause(interrupted);
    }
  }

  @Nested
  @DisplayName("task session and class")
  class Tasks {

    @Test
    @DisplayName("the worker's own session wins over the task's")
    void boundSessionWins() {
      final ChaosSession session = runtime.openSession("worker");
      try (ChaosSession.ScopeBinding ignored = session.bind()) {
        assertThat(engine.taskSession((Runnable) () -> {})).isEqualTo(session.id());
      } finally {
        session.close();
      }
    }
  }

  @Nested
  @DisplayName("FailureFactory")
  class Failures {

    @Test
    @DisplayName("a java.io open without a path fails with the bare reason")
    void javaIoOpenWithoutPath() {
      final Throwable failure =
          FailureFactory.errno(ChaosEffect.Errno.ENOENT, OperationType.FILE_IO_OPEN, null, true);
      assertThat(failure)
          .isInstanceOf(java.io.FileNotFoundException.class)
          .hasMessage(ChaosEffect.Errno.ENOENT.reason());
    }

    @Test
    @DisplayName("a client rejection without a client falls back to the generic rejection")
    void clientRejectWithoutClient() {
      final Throwable failure =
          FailureFactory.clientReject(OperationType.EXECUTOR_SUBMIT, null, "no client");
      assertThat(failure).isInstanceOf(RejectedExecutionException.class).hasMessage("no client");
    }

    @Test
    @DisplayName("a client rejection whose library is absent falls back to the generic rejection")
    void clientRejectWithoutLibrary() {
      final Throwable failure =
          FailureFactory.clientReject(OperationType.MESSAGE_SEND, null, "no kafka");
      assertThat(failure)
          .isExactlyInstanceOf(
              FailureFactory.reject(OperationType.MESSAGE_SEND, "no kafka").getClass())
          .hasMessage("no kafka");
    }

    @Test
    @DisplayName("each completion failure kind maps to its exception type")
    void completionFailureKinds() {
      assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.REJECTED, "m"))
          .isExactlyInstanceOf(RejectedExecutionException.class)
          .hasMessage("m");
      assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.ILLEGAL_STATE, "m"))
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasNoCause();
      assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.RUNTIME, "m"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessage("m");
      assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.SECURITY, "m"))
          .isExactlyInstanceOf(SecurityException.class)
          .hasMessage("m");
      assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.TIMEOUT, "m"))
          .isExactlyInstanceOf(TimeoutException.class);
    }
  }
}
