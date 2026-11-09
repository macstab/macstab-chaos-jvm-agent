package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pool name the connection-acquire hook reports, read reflectively from stand-ins shaped like a
 * pool: its {@code getPoolName()}, else the pool's class name.
 */
@DisplayName("JdbcTargetExtractor")
class JdbcTargetExtractorTest {

  /** A name whose rendering fails. */
  static final class Broken {
    @Override
    public String toString() {
      throw new IllegalStateException("broken");
    }
  }

  /** A pool declaring getPoolName(), and an overload that is not the accessor. */
  static class NamedPool {
    private final Object name;

    NamedPool(final Object name) {
      this.name = name;
    }

    Object getPoolName() {
      return name;
    }

    Object getPoolName(final String prefix) {
      return prefix + name;
    }
  }

  /** Inherits the accessor from its superclass. */
  static final class SubPool extends NamedPool {
    SubPool(final Object name) {
      super(name);
    }
  }

  /** Declares no accessor. */
  static final class UnnamedPool {}

  /** Declares no accessor, and names itself through toString(). */
  static final class SelfNamedPool {
    private final String name;

    SelfNamedPool(final String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  @Test
  @DisplayName("reads getPoolName(), also when a superclass declares it")
  void readsThePoolName() {
    assertThat(JdbcTargetExtractor.fromHikariPool(new NamedPool("orders-pool")))
        .isEqualTo("orders-pool");
    assertThat(JdbcTargetExtractor.fromHikariPool(new NamedPool("billing-pool")))
        .as("read again through the cached accessor")
        .isEqualTo("billing-pool");
    assertThat(JdbcTargetExtractor.fromHikariPool(new SubPool("reports-pool")))
        .isEqualTo("reports-pool");
  }

  @Test
  @DisplayName("falls back to the class name without an accessor, a name, or when it fails")
  void fallsBackToTheClassName() {
    assertThat(JdbcTargetExtractor.fromHikariPool(new UnnamedPool()))
        .isEqualTo(UnnamedPool.class.getName());
    assertThat(JdbcTargetExtractor.fromHikariPool(new NamedPool(null)))
        .isEqualTo(NamedPool.class.getName());
    assertThat(JdbcTargetExtractor.fromHikariPool(new NamedPool(new Broken())))
        .isEqualTo(NamedPool.class.getName());
  }

  @Test
  @DisplayName("without an accessor, a pool's own toString() names it unless it is blank")
  void readsTheOwnToString() {
    assertThat(JdbcTargetExtractor.fromHikariPool(new SelfNamedPool("ledger-pool")))
        .isEqualTo("ledger-pool");
    assertThat(JdbcTargetExtractor.fromHikariPool(new SelfNamedPool(" ")))
        .isEqualTo(SelfNamedPool.class.getName());
  }

  @Test
  @DisplayName("is null without a pool")
  void nullWithoutAPool() {
    assertThat(JdbcTargetExtractor.fromHikariPool(null)).isNull();
  }
}
