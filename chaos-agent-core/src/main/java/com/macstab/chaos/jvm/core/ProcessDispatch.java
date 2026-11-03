package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;

/**
 * Dispatch of child process starts and waits.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class ProcessDispatch {

  private final DispatchEngine engine;

  ProcessDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before a thread waits for a child process.
   *
   * @param process the process waited for
   * @param timeoutMillis the wait's timeout; {@code 0} for {@code waitFor()} without one
   * @return {@code true} when a {@code TimeoutEffect} waited the timeout out: the wait reports that
   *     the child has not exited
   * @throws Throwable an {@link InterruptedException} when a scenario rejects the wait
   */
  public boolean beforeProcessWait(final Object process, final long timeoutMillis)
      throws Throwable {
    if (engine.idle(OperationType.PROCESS_WAIT)) {
      return false;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                OperationType.PROCESS_WAIT,
                "java.lang.Process",
                null,
                programName(process),
                false,
                null,
                null,
                engine.scopeContext.currentSessionId(),
                InvocationContext.UNKNOWN,
                null,
                null,
                InvocationContext.UNKNOWN,
                null,
                (int) Math.min(timeoutMillis, Integer.MAX_VALUE)));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.TIMEOUT) {
      engine.applyDelayAndGate(decision);
      engine.sleep((Long) decision.terminalAction().returnValue());
      return true;
    }
    engine.applyPreDecision(decision);
    return false;
  }

  /** The file name of the program {@code process} runs, or {@code null} when unknown. */
  private static String programName(final Object process) {
    if (!(process instanceof Process running)) {
      return null;
    }
    return running
        .info()
        .command()
        .map(command -> java.nio.file.Path.of(command).getFileName().toString())
        .orElse(null);
  }

  /**
   * Called before a child process is started.
   *
   * @param command the program, the first element of the command; {@code null} if empty
   * @throws Throwable if an active scenario fails the start
   */
  public void beforeProcessStart(final String command) throws Throwable {
    if (engine.idle(OperationType.PROCESS_START)) {
      return;
    }
    engine.applyPreDecision(
        engine.evaluate(
            new InvocationContext(
                OperationType.PROCESS_START,
                "java.lang.ProcessBuilder",
                null,
                command,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId())));
  }
}
