package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.ReferenceQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IdentityWeakKey")
class IdentityWeakKeyTest {

  @Test
  @DisplayName("keys of one object are equal and hash alike; keys of equal objects are not equal")
  void identityNotEquality() {
    final String one = new String("value");
    final String other = new String("value");
    final IdentityWeakKey stored = new IdentityWeakKey(one, new ReferenceQueue<>());

    final Object sameKey = stored;
    assertThat(stored.equals(sameKey)).as("the key itself").isTrue();
    assertThat(new IdentityWeakKey(one)).isEqualTo(stored).hasSameHashCodeAs(stored);
    assertThat(new IdentityWeakKey(other)).isNotEqualTo(stored);
    assertThat(stored).isNotEqualTo(one);
  }

  @Test
  @DisplayName("a key whose object is gone equals only itself")
  void clearedKeyEqualsOnlyItself() {
    final Object referent = new Object();
    final IdentityWeakKey cleared = new IdentityWeakKey(referent);
    cleared.clear();

    final Object sameKey = cleared;
    assertThat(cleared.equals(sameKey)).as("the key itself").isTrue();
    assertThat(cleared).isNotEqualTo(new IdentityWeakKey(referent));
  }
}
