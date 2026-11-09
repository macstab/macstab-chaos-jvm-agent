package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Interns a configurable number of distinct strings into the JVM's string table, simulating the
 * pattern of interned-string exhaustion seen in applications that intern user input or request
 * identifiers.
 *
 * <p>The interned strings live on the heap, and the string table that maps them grows with every
 * distinct one: a long table makes every {@code intern()} of the application slower.
 *
 * <p>String {@code i} is {@code i} written in base 256, one Latin-1 character per digit, padded to
 * exactly {@code stringLengthBytes} characters (one byte each in a compact string), so the strings
 * are distinct as long as {@code internCount <= 256^stringLengthBytes}; the validator refuses more.
 * Two such stressors of one length intern the same strings, so the table grows by the larger count.
 * Strong references to the interned strings are retained so they cannot be collected while this
 * stressor is active.
 *
 * <p>{@link #close()} nulls all references; the next collection removes the strings from the table.
 */
final class StringInternPressureStressor implements ManagedStressor {

  private static final AgentLog LOGGER =
      AgentLog.named(StringInternPressureStressor.class.getName());

  /**
   * Upper bound on {@code stringLengthBytes}, refused above by the validator: 1 MiB is well above
   * any realistic interned-string fixture and still fits in a single array.
   */
  static final int MAX_STRING_LENGTH_BYTES = 1 << 20;

  /**
   * Upper bound on {@code internCount}, refused above by the validator: an unbounded count
   * multiplied by a large length exhausts the heap before any operator telemetry fires.
   */
  static final int MAX_INTERN_COUNT = 1_000_000;

  private volatile @Nullable List<String> internedStrings;

  StringInternPressureStressor(final ChaosEffect.StringInternPressureEffect effect) {
    final int lengthBytes = effect.stringLengthBytes();
    final int internCount = effect.internCount();
    final List<String> strings = new ArrayList<>(internCount);
    for (int i = 0; i < internCount; i++) {
      strings.add(buildAndIntern(i, lengthBytes));
    }
    this.internedStrings = List.copyOf(strings);
    LOGGER.fine(
        () ->
            "StringInternPressureStressor interned "
                + strings.size()
                + " strings of "
                + lengthBytes
                + " bytes each");
  }

  @Override
  public void close() {
    internedStrings = null;
  }

  /** Returns the number of currently retained interned strings, or 0 after {@link #close()}. */
  int internedCount() {
    final List<String> snapshot = internedStrings;
    return snapshot == null ? 0 : snapshot.size();
  }

  /** Returns the number of distinct retained strings, or 0 after {@link #close()}; a test seam. */
  int distinctCount() {
    final List<String> snapshot = internedStrings;
    return snapshot == null ? 0 : new java.util.HashSet<>(snapshot).size();
  }

  /** Interns {@code index} in base 256, one Latin-1 character per digit, most significant first. */
  private static String buildAndIntern(final int index, final int lengthBytes) {
    final char[] digits = new char[lengthBytes];
    int rest = index;
    for (int position = lengthBytes - 1; position >= 0 && rest != 0; position--) {
      digits[position] = (char) (rest & 0xFF);
      rest >>>= 8;
    }
    return new String(digits).intern();
  }
}
