package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import org.jspecify.annotations.Nullable;

/**
 * What the chaos engine does at the end of a decision: one record per {@link TerminalKind}, each
 * carrying exactly the payload its kind needs.
 *
 * <p>Every action names the scenario that produced it ({@code null} for an action made outside a
 * scenario), so fallback and diagnostic log messages can be traced back to it.
 */
sealed interface TerminalAction {

  /**
   * Returns the kind of this action.
   *
   * @return the kind
   */
  TerminalKind kind();

  /**
   * Returns the id of the scenario that produced this action.
   *
   * @return the scenario id, or {@code null} outside a scenario
   */
  @Nullable String scenarioId();

  /**
   * Throw {@code failure} from the interception site.
   *
   * @param failure the exception to throw
   * @param scenarioId the producing scenario
   */
  record Throw(Throwable failure, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.THROW;
    }
  }

  /**
   * Return {@code value} from the interception site.
   *
   * @param value the value; {@link Boolean#FALSE} for a refused operation, {@code null} for none
   * @param scenarioId the producing scenario
   */
  record Return(@Nullable Boolean value, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.RETURN;
    }
  }

  /**
   * Complete a future exceptionally with {@code failure}.
   *
   * @param failure the exception to complete with
   * @param scenarioId the producing scenario
   */
  record CompleteExceptionally(Throwable failure, @Nullable String scenarioId)
      implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.COMPLETE_EXCEPTIONALLY;
    }
  }

  /**
   * Suppress the operation.
   *
   * @param scenarioId the producing scenario
   */
  record Suppress(@Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.SUPPRESS;
    }
  }

  /**
   * Corrupt the method's return value by {@code effect} on exit.
   *
   * @param effect the corruption to apply
   * @param scenarioId the producing scenario
   */
  record CorruptReturn(ChaosEffect.ReturnValueCorruptionEffect effect, @Nullable String scenarioId)
      implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.CORRUPT_RETURN;
    }
  }

  /**
   * Cap the byte count of a read or write at {@code limit}.
   *
   * @param limit the most bytes the call may move
   * @param scenarioId the producing scenario
   */
  record CapLength(long limit, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.CAP_LENGTH;
    }
  }

  /**
   * Flip one bit in the bytes a read returns, chosen from {@code seed}.
   *
   * @param seed the seed of the bit to flip
   * @param scenarioId the producing scenario
   */
  record CorruptRead(long seed, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.CORRUPT_READ;
    }
  }

  /**
   * Close the socket abortively.
   *
   * @param scenarioId the producing scenario
   */
  record AbortiveClose(@Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.ABORTIVE_CLOSE;
    }
  }

  /**
   * Wait {@code waitMillis}, then throw {@code failure}.
   *
   * @param waitMillis the call's own timeout
   * @param failure the timeout exception
   * @param scenarioId the producing scenario
   */
  record Timeout(long waitMillis, Throwable failure, @Nullable String scenarioId)
      implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.TIMEOUT;
    }
  }

  /**
   * The effect does not apply to this call.
   *
   * @param scenarioId the producing scenario
   */
  record NotApplicable(@Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.NOT_APPLICABLE;
    }
  }

  /**
   * Reshape a DNS answer by {@code shape}.
   *
   * @param shape the effect and the seed of its random choices
   * @param scenarioId the producing scenario
   */
  record DnsAnswer(DnsShape shape, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.DNS_SHAPE;
    }
  }

  /**
   * Resolve {@code host} instead of the requested name.
   *
   * @param host the host to resolve
   * @param scenarioId the producing scenario
   */
  record DnsRewrite(String host, @Nullable String scenarioId) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.REWRITE;
    }
  }

  /**
   * Connect to the endpoint of {@code redirect} instead.
   *
   * @param redirect the redirect effect naming the endpoint
   * @param scenarioId the producing scenario
   */
  record Redirect(ChaosEffect.RedirectEffect redirect, @Nullable String scenarioId)
      implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.REDIRECT;
    }
  }
}
