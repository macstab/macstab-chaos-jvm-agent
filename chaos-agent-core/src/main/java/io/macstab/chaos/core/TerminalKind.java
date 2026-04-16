package io.macstab.chaos.core;

enum TerminalKind {
  /** Throw the enclosed {@link TerminalAction#throwable()} from the interception site. */
  THROW,
  /**
   * Return a specific value from the interception site. When {@link TerminalAction#returnValue()}
   * is {@link Boolean#FALSE} and the call site uses {@link
   * io.macstab.chaos.core.ChaosRuntime#applyPreDecision}, a {@link
   * java.util.concurrent.RejectedExecutionException} is thrown instead.
   */
  RETURN,
  /**
   * Complete a {@link java.util.concurrent.CompletableFuture} exceptionally with the enclosed
   * throwable.
   */
  COMPLETE_EXCEPTIONALLY,
  /**
   * Silently suppress the operation by substituting a no-op wrapper. Used exclusively for executor
   * and fork-join task decoration — the original task is replaced with an empty lambda so the
   * executor continues to function normally while the submitted work is discarded.
   */
  SUPPRESS,
  /**
   * Corrupt the return value of an instrumented method on exit. The actual corruption is computed
   * by {@link ReturnValueCorruptor} using the strategy from {@link
   * io.macstab.chaos.api.ChaosEffect.ReturnValueCorruptionEffect}.
   */
  CORRUPT_RETURN,
}
