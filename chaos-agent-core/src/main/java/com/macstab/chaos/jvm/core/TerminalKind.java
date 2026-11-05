package com.macstab.chaos.jvm.core;

/** What a {@link TerminalAction} does; each kind has its own record carrying its payload. */
enum TerminalKind {
  /** Throw from the interception site: {@link TerminalAction.Throw}. */
  THROW,
  /**
   * Return a specific value from the interception site: {@link TerminalAction.Return}. When the
   * value is {@link Boolean#FALSE} and the call site uses {@code DispatchEngine.applyPreDecision},
   * a {@link java.util.concurrent.RejectedExecutionException} is thrown instead.
   */
  RETURN,
  /**
   * Complete a {@link java.util.concurrent.CompletableFuture} exceptionally: {@link
   * TerminalAction.CompleteExceptionally}.
   */
  COMPLETE_EXCEPTIONALLY,
  /**
   * Silently suppress the operation: {@link TerminalAction.Suppress}. For executor and fork-join
   * task decoration the original task is replaced with an empty one, so the executor keeps working
   * while the submitted work is discarded.
   */
  SUPPRESS,
  /**
   * Corrupt the return value of an instrumented method on exit, computed by {@link
   * ReturnValueCorruptor}: {@link TerminalAction.CorruptReturn}.
   */
  CORRUPT_RETURN,
  /** Cap the byte count of a read or write: {@link TerminalAction.CapLength}. */
  CAP_LENGTH,
  /** Flip one bit in the bytes the read returns: {@link TerminalAction.CorruptRead}. */
  CORRUPT_READ,
  /** Close the socket abortively ({@code SO_LINGER} 0): {@link TerminalAction.AbortiveClose}. */
  ABORTIVE_CLOSE,
  /** Wait out the call's own timeout, then fail it: {@link TerminalAction.Timeout}. */
  TIMEOUT,
  /**
   * The effect does not apply to this call (a {@code TimeoutEffect} on a socket without a timeout):
   * the scenario neither takes effect nor counts an application. {@link
   * TerminalAction.NotApplicable}.
   */
  NOT_APPLICABLE,
  /** Reshape a DNS answer: {@link TerminalAction.DnsAnswer}. */
  DNS_SHAPE,
  /** Resolve another host instead: {@link TerminalAction.DnsRewrite}. */
  REWRITE,
  /** Connect to another endpoint instead: {@link TerminalAction.Redirect}. */
  REDIRECT,
}
