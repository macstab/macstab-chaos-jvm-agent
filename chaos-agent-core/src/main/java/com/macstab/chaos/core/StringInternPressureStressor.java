package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Interns a configurable number of unique strings into the JVM's native string table (Metaspace),
 * simulating the pattern of interned-string exhaustion seen in applications that intern user input
 * or request identifiers.
 *
 * <p>Unlike heap pressure, interned strings reside in Metaspace and are not collected by normal GC
 * until the classloader that owns them is collected. This creates Metaspace pressure distinct from
 * class-loading pressure.
 *
 * <p>Each string is unique (UUID-prefixed) and of exactly {@code stringLengthBytes} characters.
 * Strong references to the interned strings are retained so they cannot be collected while this
 * stressor is active.
 *
 * <p>{@link #close()} nulls all references. The strings may remain in the native string table until
 * the next full GC that collects the associated classloader.
 */
final class StringInternPressureStressor implements ManagedStressor {

  private static final Logger LOGGER =
      Logger.getLogger(StringInternPressureStressor.class.getName());

  /**
   * Upper bound on {@code stringLengthBytes}. Guards the {@code new StringBuilder(lengthBytes)}
   * allocation in {@link #buildAndIntern} against OOM / {@code NegativeArraySizeException} if the
   * effect is constructed with a pathological value. 1 MiB is well above any realistic
   * interned-string fixture and still fits in a single char[] allocation.
   */
  private static final int MAX_STRING_LENGTH_BYTES = 1 << 20;

  /**
   * Upper bound on {@code internCount}. An unbounded value multiplied by a large length can exhaust
   * Metaspace before any operator telemetry fires. 1M interned strings is an order of magnitude
   * above typical stress runs.
   */
  private static final int MAX_INTERN_COUNT = 1_000_000;

  private volatile List<String> internedStrings;

  StringInternPressureStressor(final ChaosEffect.StringInternPressureEffect effect) {
    final int lengthBytes = clamp(effect.stringLengthBytes(), 1, MAX_STRING_LENGTH_BYTES);
    final int internCount = clamp(effect.internCount(), 0, MAX_INTERN_COUNT);
    final List<String> strings = new ArrayList<>(internCount);
    for (int i = 0; i < internCount; i++) {
      strings.add(buildAndIntern(i, lengthBytes));
    }
    this.internedStrings = List.copyOf(strings);
    LOGGER.fine(
        "StringInternPressureStressor interned "
            + strings.size()
            + " strings of "
            + lengthBytes
            + " bytes each");
  }

  private static int clamp(final int value, final int min, final int max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
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

  private static String buildAndIntern(final int index, final int lengthBytes) {
    final String uuid = UUID.randomUUID().toString().replace("-", "");
    final StringBuilder sb = new StringBuilder(lengthBytes);
    sb.append(uuid, 0, Math.min(uuid.length(), lengthBytes));
    while (sb.length() < lengthBytes) {
      sb.append((char) ('a' + (index % 26)));
    }
    return sb.toString().intern();
  }
}
