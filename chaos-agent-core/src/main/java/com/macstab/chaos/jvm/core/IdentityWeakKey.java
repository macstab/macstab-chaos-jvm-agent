package com.macstab.chaos.jvm.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import org.jspecify.annotations.Nullable;

/**
 * A map key that holds its object weakly and compares it by identity: two keys are equal while both
 * still refer to the same object. The object's own {@code equals} and {@code hashCode} are never
 * called, so equal objects keep their own entries and a throwing {@code hashCode} cannot fail a
 * lookup. A key whose object was collected equals only itself, so the entry it leaves is removed
 * through the key taken from its {@link ReferenceQueue}.
 */
final class IdentityWeakKey extends WeakReference<Object> {

  private final int hash;

  /**
   * A key to look an entry up with.
   *
   * @param referent the object
   */
  IdentityWeakKey(final Object referent) {
    this(referent, null);
  }

  /**
   * A key to store, enqueued on {@code queue} once its object is collected.
   *
   * @param referent the object
   * @param queue the queue of collected keys, or {@code null}
   */
  IdentityWeakKey(final Object referent, final @Nullable ReferenceQueue<Object> queue) {
    super(referent, queue);
    this.hash = System.identityHashCode(referent);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // identity is the point
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof IdentityWeakKey key)) {
      return false;
    }
    final Object referent = get();
    return referent != null && referent == key.get();
  }
}
