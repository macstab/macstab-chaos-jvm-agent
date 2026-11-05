package com.macstab.chaos.jvm.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The documentation of the bridge handles is the {@link BootstrapDispatcher} it describes: the slot
 * table of {@code docs/instrumentation.md} §7 lists every slot constant at its index, every {@code
 * NAME = n} the document mentions is that constant's index, and every handle count stated in the
 * docs or the README is {@link BootstrapDispatcher#HANDLE_COUNT}.
 */
@DisplayName("Bridge handle documentation matches BootstrapDispatcher")
class HandleTableDocTest {

  private static final Pattern TABLE_ROW =
      Pattern.compile("^\\| (\\d+) +\\| `([A-Z0-9_]+)` +\\|", Pattern.MULTILINE);
  private static final Pattern SLOT_MENTION = Pattern.compile("`([A-Z][A-Z0-9_]+) = (\\d+)`");
  private static final Pattern COUNT =
      Pattern.compile(
          "(\\d+)(?: (?:interception handles|Interception Handles|handle slots)|-slot)"
              + "|HANDLE_COUNT`?[^()\\n.]{0,20}\\((?:currently )?(\\d+)\\)"
              + "|HANDLE_COUNT`? is (\\d+)");

  @Test
  @DisplayName("the slot table of §7 lists every slot at its index")
  void slotTable() throws IOException {
    final Map<Integer, String> documented = new TreeMap<>();
    final Matcher row = TABLE_ROW.matcher(read("docs/instrumentation.md"));
    while (row.find()) {
      documented.put(Integer.parseInt(row.group(1)), row.group(2));
    }
    assertThat(documented).isEqualTo(slots());
  }

  @Test
  @DisplayName("every slot named with its index carries the index of its constant")
  void slotMentions() throws IOException {
    final Map<String, Integer> byName = new TreeMap<>();
    slots().forEach((index, name) -> byName.put(name, index));
    final Matcher mention = SLOT_MENTION.matcher(read("docs/instrumentation.md"));
    final SoftAssertions softly = new SoftAssertions();
    int mentions = 0;
    while (mention.find()) {
      mentions++;
      softly
          .assertThat(byName.get(mention.group(1)))
          .as(mention.group())
          .isEqualTo(Integer.valueOf(mention.group(2)));
    }
    softly.assertThat(mentions).as("slot mentions found").isPositive();
    softly.assertAll();
  }

  @Test
  @DisplayName("every handle count in the documentation is HANDLE_COUNT")
  void handleCounts() throws IOException {
    final List<String> wrong = new ArrayList<>();
    int counts = 0;
    for (final Path doc : documents()) {
      final Matcher count = COUNT.matcher(Files.readString(doc));
      while (count.find()) {
        counts++;
        for (int group = 1; group <= count.groupCount(); group++) {
          if (count.group(group) != null
              && Integer.parseInt(count.group(group)) != BootstrapDispatcher.HANDLE_COUNT) {
            wrong.add(root().relativize(doc) + ": " + count.group());
          }
        }
      }
    }
    assertThat(counts).as("handle counts found").isPositive();
    assertThat(wrong).as("not %d", BootstrapDispatcher.HANDLE_COUNT).isEmpty();
  }

  /** Index to name of every slot constant (not the {@code SCOPE_*} ones or the count). */
  private static Map<Integer, String> slots() {
    final Map<Integer, String> slots = new TreeMap<>();
    for (final Field field : BootstrapDispatcher.class.getFields()) {
      final int modifiers = field.getModifiers();
      if (field.getType() == int.class
          && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers)
          && !field.getName().startsWith("SCOPE_")
          && !field.getName().equals("HANDLE_COUNT")) {
        try {
          slots.put(field.getInt(null), field.getName());
        } catch (final IllegalAccessException unreadable) {
          throw new IllegalStateException(unreadable);
        }
      }
    }
    assertThat(slots).hasSize(BootstrapDispatcher.HANDLE_COUNT);
    return slots;
  }

  private static List<Path> documents() throws IOException {
    final List<Path> documents = new ArrayList<>();
    documents.add(root().resolve("README.md"));
    try (var docs = Files.list(root().resolve("docs"))) {
      docs.filter(path -> path.toString().endsWith(".md")).sorted().forEach(documents::add);
    }
    return documents;
  }

  private static String read(final String path) throws IOException {
    return Files.readString(root().resolve(path));
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isRegularFile(root.resolve("docs/instrumentation.md"))) {
      root = root.getParent();
    }
    return root;
  }
}
