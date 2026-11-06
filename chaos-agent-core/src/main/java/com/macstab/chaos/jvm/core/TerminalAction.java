package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import org.jspecify.annotations.Nullable;

/**
 * What the chaos engine does at the end of a decision: one record per {@link TerminalKind}, each
 * carrying exactly the payload its kind needs.
 */
sealed interface TerminalAction {

  /**
   * Returns the kind of this action.
   *
   * @return the kind
   */
  TerminalKind kind();

  /**
   * Throw {@code failure} from the interception site.
   *
   * @param failure the exception to throw
   */
  record Throw(Throwable failure) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.THROW;
    }
  }

  /**
   * Return {@code value} from the interception site.
   *
   * @param value the value; {@link Boolean#FALSE} for a refused operation, {@code null} for none
   */
  record Return(@Nullable Boolean value) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.RETURN;
    }
  }

  /**
   * Complete a future exceptionally with {@code failure}.
   *
   * @param failure the exception to complete with
   */
  record CompleteExceptionally(Throwable failure) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.COMPLETE_EXCEPTIONALLY;
    }
  }

  /** Suppress the operation. */
  record Suppress() implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.SUPPRESS;
    }
  }

  /**
   * Corrupt the method's return value by {@code effect} on exit.
   *
   * @param effect the corruption to apply
   * @param scenarioId the scenario that fired, named when a strategy falls back
   */
  record CorruptReturn(ChaosEffect.ReturnValueCorruptionEffect effect, String scenarioId)
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
   */
  record CapLength(long limit) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.CAP_LENGTH;
    }
  }

  /**
   * Flip one bit in the bytes a read returns, chosen from {@code seed}.
   *
   * @param seed the seed of the bit to flip
   */
  record CorruptRead(long seed) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.CORRUPT_READ;
    }
  }

  /** Close the socket abortively. */
  record AbortiveClose() implements TerminalAction {
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
   */
  record Timeout(long waitMillis, Throwable failure) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.TIMEOUT;
    }
  }

  /** The effect does not apply to this call. */
  record NotApplicable() implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.NOT_APPLICABLE;
    }
  }

  /**
   * Reshape a DNS answer by {@code shape}.
   *
   * @param shape the effect and the seed of its random choices
   */
  record DnsAnswer(DnsShape shape) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.DNS_SHAPE;
    }
  }

  /**
   * Resolve {@code host} instead of the requested name.
   *
   * @param host the host to resolve
   */
  record DnsRewrite(String host) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.REWRITE;
    }
  }

  /**
   * Connect to the endpoint of {@code redirect} instead.
   *
   * @param redirect the redirect effect naming the endpoint
   */
  record Redirect(ChaosEffect.RedirectEffect redirect) implements TerminalAction {
    @Override
    public TerminalKind kind() {
      return TerminalKind.REDIRECT;
    }
  }
}
