package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The core raises the bootstrap dispatcher's depth through a class and two methods it names as
 * strings ({@code DispatchDepth}), because it cannot compile against the instrumentation. A rename
 * of either would turn L3 off silently: the lookup failure reads as "the agent is not installed".
 * This test, which sees both sides, fails on such a rename instead.
 */
@DisplayName("The core's link to the bootstrap dispatcher's depth")
class DispatchDepthLinkTest {

  @Test
  @DisplayName("names the bootstrap dispatcher and its public static void depth methods")
  void namesTheDispatcher() throws Exception {
    final Class<?> depth = Class.forName("com.macstab.chaos.jvm.core.DispatchDepth");

    assertThat(constant(depth, "BOOTSTRAP_DISPATCHER"))
        .isEqualTo(BootstrapDispatcher.class.getName());
    for (final String method : new String[] {"BEGIN_INTERNAL_CALL", "END_INTERNAL_CALL"}) {
      final Method target = BootstrapDispatcher.class.getMethod(constant(depth, method));
      assertThat(Modifier.isStatic(target.getModifiers())).as(method).isTrue();
      assertThat(target.getReturnType()).as(method).isEqualTo(void.class);
    }
  }

  private static String constant(final Class<?> owner, final String name) throws Exception {
    final Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }
}
