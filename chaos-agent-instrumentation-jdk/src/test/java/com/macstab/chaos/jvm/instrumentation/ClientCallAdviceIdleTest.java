package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import com.macstab.chaos.jvm.instrumentation.bridge.BridgeDelegate;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Advice that reads a client's target reflectively returns before it reads anything while no
 * scenario is indexed for its operation: the extraction is paid only when a scenario could match.
 * The bridge is a {@link Proxy} of {@link BridgeDelegate} wired through the installer's own handle
 * table, idle for every operation.
 */
@DisplayName("Client call advice while idle")
class ClientCallAdviceIdleTest {

  /** A Kafka consumer stand-in whose subscription read is counted. */
  public static final class CountingConsumer {
    final AtomicInteger reads = new AtomicInteger();

    /**
     * The subscribed topics.
     *
     * @return one topic
     */
    public Set<String> subscription() {
      reads.incrementAndGet();
      return Set.of("orders");
    }
  }

  private final List<String> delegateCalls = new CopyOnWriteArrayList<>();

  @AfterEach
  void uninstall() {
    BootstrapDispatcher.install(null, null);
  }

  @Test
  @DisplayName("an idle Kafka poll reads no subscription and reaches no client dispatch")
  void idlePollExtractsNothing() throws Throwable {
    BootstrapDispatcher.install(idleDelegate(), JdkInstrumentationInstaller.buildMethodHandles());
    final CountingConsumer consumer = new CountingConsumer();

    ClientCallAdvice.KafkaPoll.enter(consumer);

    assertThat(consumer.reads).as("subscription() reads").hasValue(0);
    assertThat(delegateCalls).doesNotContain("beforeClientCall");
  }

  /** A delegate that is idle for every operation and records every other call. */
  private Object idleDelegate() {
    return Proxy.newProxyInstance(
        BridgeDelegate.class.getClassLoader(),
        new Class<?>[] {BridgeDelegate.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("idle")) {
            return true;
          }
          delegateCalls.add(method.getName());
          final Class<?> type = method.getReturnType();
          if (type == boolean.class) {
            return false;
          }
          if (type == int.class) {
            return 0;
          }
          if (type == long.class) {
            return 0L;
          }
          return null;
        });
  }
}
