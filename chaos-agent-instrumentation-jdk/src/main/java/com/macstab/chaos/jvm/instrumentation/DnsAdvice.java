package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} classes for name resolution ({@code DNS_RESOLVE}, {@code DNS_ANSWER},
 * {@code DNS_REVERSE}).
 *
 * <p>Each nested class targets one method or overload family. The outer class is not instantiated;
 * it exists only as a namespace.
 */
final class DnsAdvice {
  private DnsAdvice() {}

  // ── Reverse DNS (DNS_REVERSE) ────────────────────────────────────────────

  /**
   * Intercepts {@code InetAddress.getHostFromNameService(InetAddress[, boolean])} (the {@code
   * boolean} is gone in JDK 25): a failed reverse lookup returns the IP literal, as the JDK reports
   * one.
   */
  static final class DnsReverseAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static boolean enter(@Advice.Argument(0) final java.net.InetAddress address) throws Throwable {
      return BootstrapDispatcher.reverseLookupFails(address);
    }

    @Advice.OnMethodExit
    static void exit(
        @Advice.Enter final boolean failed,
        @Advice.Argument(0) final java.net.InetAddress address,
        @Advice.Return(readOnly = false) String name) {
      if (failed) {
        name = address.getHostAddress();
      }
    }
  }

  // ── B15: DNS resolution ───────────────────────────────────────────────────

  /**
   * Intercepts {@link java.net.InetAddress#getByName(String)} and {@link
   * java.net.InetAddress#getAllByName(String)}. {@code getByName} delegates to {@code
   * getAllByName}; the {@code SCOPE_DNS} call-depth scope evaluates a lookup once.
   *
   * <p>These are static methods, so no {@code @Advice.This} is present. The hostname argument is
   * forwarded to the dispatcher, which can inject latency or throw {@link
   * java.net.UnknownHostException}. A {@code null} hostname is forwarded as the empty name: the JDK
   * answers both with the loopback address without a lookup, and {@code null} at the dispatcher
   * stands for {@code getLocalHost()} ({@link DnsLocalHostAdvice}), which does look its name up.
   */
  static final class DnsResolveAdvice {
    @Advice.OnMethodEnter
    static void enter(@Advice.Argument(value = 0, readOnly = false) String hostname)
        throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_DNS)) {
        try {
          hostname = BootstrapDispatcher.beforeDnsResolve(hostname == null ? "" : hostname);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
    }
  }

  /**
   * Intercepts {@link java.net.InetAddress#getLocalHost()}, which has no arguments.
   *
   * <p>Passes {@code null} as the hostname to signal the local-host lookup case to the dispatcher.
   */
  static final class DnsLocalHostAdvice {
    @Advice.OnMethodEnter
    static void enter() throws Throwable {
      if (BootstrapDispatcher.enterScope(BootstrapDispatcher.SCOPE_DNS)) {
        try {
          BootstrapDispatcher.beforeDnsResolve(null);
        } catch (final Throwable throwable) {
          BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
          throw throwable;
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit() {
      BootstrapDispatcher.exitScope(BootstrapDispatcher.SCOPE_DNS);
    }
  }

  /**
   * Reshapes the answer of {@link java.net.InetAddress#getAllByName(String)} under a {@code
   * DnsAnswerEffect}: on return, or on an {@link java.net.UnknownHostException} an override can
   * replace. {@code getByName} resolves through it, so both see the reshaped answer. A {@code null}
   * hostname is the loopback address without a lookup, forwarded as the empty name.
   */
  static final class DnsAnswerAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Advice.Argument(0) final String hostname,
        @Advice.Return(readOnly = false) java.net.InetAddress[] answer,
        @Advice.Thrown(readOnly = false) Throwable failure)
        throws Throwable {
      if (failure != null && !(failure instanceof java.net.UnknownHostException)) {
        return;
      }
      final java.net.InetAddress[] reshaped =
          BootstrapDispatcher.afterDnsAnswer(hostname == null ? "" : hostname, answer, failure);
      if (reshaped != null) {
        answer = reshaped;
        failure = null;
      }
    }
  }
}
