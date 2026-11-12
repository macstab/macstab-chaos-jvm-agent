package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gRPC reject is refused at activation ({@code CompatibilityValidator}) unless the advice ({@link
 * ClientCallExtractor}) can build gRPC's {@code FailingClientStream}. The two layers cannot share
 * the list of constructor shapes (the core cannot see the bridge), so each holds a copy; this test,
 * which sees both, fails when the copies drift (ADR 0005: a refusal matches what the advice can
 * build).
 */
@DisplayName("The gRPC failing-stream constructor shapes")
class GrpcFailingStreamShapesTest {

  @Test
  @DisplayName("the validator and the advice accept the same class and constructor shapes")
  void validatorAndAdviceAgree() throws Exception {
    final Class<?> validator = Class.forName("com.macstab.chaos.jvm.core.CompatibilityValidator");

    assertThat(read(validator, "GRPC_FAILING_STREAM_SHAPES"))
        .isEqualTo(read(ClientCallExtractor.class, "GRPC_FAILING_STREAM_SHAPES"));
    assertThat(read(validator, "GRPC_FAILING_STREAM"))
        .isEqualTo(read(ClientCallExtractor.class, "GRPC_FAILING_STREAM"));
  }

  private static Object read(final Class<?> owner, final String name) throws Exception {
    final Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }
}
