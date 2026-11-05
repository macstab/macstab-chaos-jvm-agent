package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The plan pipeline on arbitrary input. Whatever the input, parsing returns a plan or throws {@link
 * ConfigLoadException}, and validating a parsed scenario returns or throws {@link
 * ChaosValidationException} / {@link ChaosActivationException}: no other exception, no hang. Two
 * sources of input: mutations of the seed plans in {@code fuzz-plans.json}, and plans generated
 * from the published plan schema, which reach the record constructors and validators of every
 * selector and effect.
 *
 * <p>Seeded and replayable: {@code ./gradlew :chaos-agent-bootstrap:test --tests '*PlanFuzzTest*'
 * -PfuzzSeed=<seed> -PfuzzIterations=<n>} (defaults 20261103 and 4000); a failure names the seed,
 * the iteration and the input.
 */
@DisplayName("Plan parser and validator fuzzing")
class PlanFuzzTest {

  private static final long SEED = Long.getLong("chaos.fuzz.seed", 20261103L);
  private static final int ITERATIONS = Integer.getInteger("chaos.fuzz.iterations", 4000);
  private static final long MAX_NANOS_PER_INPUT = 1_000_000_000L;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final ObjectMapper json = new ObjectMapper();

  @AfterEach
  void close() {
    runtime.close();
  }

  @Test
  @DisplayName("mutated documentation examples are parsed or rejected, never crash")
  void mutatedExamples() throws IOException {
    final List<String> corpus = seedPlans();
    assertThat(corpus).as("seed plans").hasSizeGreaterThan(20);
    final SplittableRandom random = new SplittableRandom(SEED);
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      final String input = mutate(corpus.get(random.nextInt(corpus.size())), random);
      check(input, "mutation", iteration);
    }
  }

  @Test
  @DisplayName("plans generated from the schema are parsed or rejected, never crash")
  void generatedPlans() throws IOException {
    final JsonNode schema;
    try (InputStream in = ChaosPlanMapper.class.getResourceAsStream("chaos-plan.schema.json")) {
      assertThat(in).as("published plan schema").isNotNull();
      schema = json.readTree(in);
    }
    final SplittableRandom random = new SplittableRandom(SEED ^ 0x5DEECE66DL);
    int parsed = 0;
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      final JsonNode plan = generate(schema, schema, random, 0);
      if (check(json.writeValueAsString(plan), "generation", iteration)) {
        parsed++;
      }
    }
    // The generator must reach the validators, not only the parser's error paths.
    assertThat(parsed).as("generated plans that parsed").isGreaterThan(ITERATIONS / 20);
  }

  /** Runs one input through the pipeline; returns whether it parsed. */
  private boolean check(final String input, final String source, final int iteration) {
    final long start = System.nanoTime();
    final ChaosPlan plan;
    try {
      plan = ChaosPlanMapper.read(input);
    } catch (final ConfigLoadException rejected) {
      assertWithinTime(start, input, source, iteration);
      return false;
    } catch (final Throwable crash) {
      throw failure("parsing", input, source, iteration, crash);
    }
    for (final ChaosScenario scenario : plan.scenarios()) {
      try {
        runtime.validate(scenario);
      } catch (final ChaosValidationException | ChaosActivationException rejected) {
        // a well-formed but meaningless scenario: the defined outcome
      } catch (final Throwable crash) {
        throw failure("validating", input, source, iteration, crash);
      }
    }
    assertWithinTime(start, input, source, iteration);
    return true;
  }

  private static void assertWithinTime(
      final long start, final String input, final String source, final int iteration) {
    assertThat(System.nanoTime() - start)
        .as("%s %d (seed %d) took too long: %s", source, iteration, SEED, abbreviate(input))
        .isLessThan(MAX_NANOS_PER_INPUT);
  }

  private static AssertionError failure(
      final String stage,
      final String input,
      final String source,
      final int iteration,
      final Throwable crash) {
    return new AssertionError(
        stage
            + " "
            + source
            + " "
            + iteration
            + " (seed "
            + SEED
            + ") threw "
            + crash
            + " for input: "
            + abbreviate(input),
        crash);
  }

  private static String abbreviate(final String input) {
    return input.length() <= 600 ? input : input.substring(0, 600) + "… (" + input.length() + ")";
  }

  // ── corpus and mutations ──────────────────────────────────────────────────

  /** The seed plans: one of every selector family and effect kind, as a user writes them. */
  private List<String> seedPlans() throws IOException {
    final List<String> corpus = new ArrayList<>();
    try (InputStream plans = PlanFuzzTest.class.getResourceAsStream("fuzz-plans.json")) {
      assertThat(plans).as("fuzz-plans.json").isNotNull();
      for (final JsonNode plan : json.readTree(plans)) {
        corpus.add(plan.toPrettyString());
      }
    }
    return corpus;
  }

  private static final String[] TOKENS = {
    "{",
    "}",
    "[",
    "]",
    ",",
    ":",
    "\"",
    "null",
    "true",
    "-",
    "0",
    "1e999",
    "\\u0000",
    "\u00e9",
    "\uD83D\uDE00",
    " ",
    "\n"
  };

  private static final String[] VALUES = {
    "null",
    "true",
    "0",
    "-1",
    "9223372036854775808",
    "-9223372036854775809",
    "1e999",
    "1e-999",
    "0.5",
    "-0.0",
    "\"\"",
    "\"x\"",
    "\"PT-1S\"",
    "\"P99999999999D\"",
    "\"*\"",
    "\"(a+)+$\"",
    "\"\\u0000\"",
    "\"../../etc/passwd\"",
    "[]",
    "{}",
    "[null]",
    "{\"type\": \"unknown\"}",
    "\"" + "x".repeat(5000) + "\""
  };

  private static String mutate(final String seed, final SplittableRandom random) {
    final StringBuilder text = new StringBuilder(seed);
    final int edits = 1 + random.nextInt(3);
    for (int edit = 0; edit < edits && text.length() > 0; edit++) {
      final int at = random.nextInt(text.length());
      switch (random.nextInt(7)) {
        case 0 -> text.delete(at, Math.min(text.length(), at + 1 + random.nextInt(8)));
        case 1 -> text.insert(at, TOKENS[random.nextInt(TOKENS.length)]);
        case 2 -> {
          final int end = Math.min(text.length(), at + 1 + random.nextInt(40));
          text.insert(end, text.substring(at, end));
        }
        case 3 -> replaceValue(text, at, VALUES[random.nextInt(VALUES.length)]);
        case 4 -> {
          // wrap a value in deep nesting: the reader's depth limit must hold
          final int depth = 1 + random.nextInt(1500);
          text.insert(at, "[".repeat(depth)).append("]".repeat(depth));
        }
        case 5 -> renameKey(text, at, random);
        default -> text.setCharAt(at, (char) random.nextInt(0x20, 0x7f));
      }
    }
    return text.toString();
  }

  /** Replaces the value after the next {@code ":"} from {@code at} with {@code value}. */
  private static void replaceValue(final StringBuilder text, final int at, final String value) {
    final int colon = text.indexOf(":", at);
    if (colon < 0) {
      return;
    }
    int end = colon + 1;
    int depth = 0;
    while (end < text.length()) {
      final char c = text.charAt(end);
      if ((c == ',' || c == '}' || c == ']') && depth == 0) {
        break;
      }
      if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
      }
      end++;
    }
    text.replace(colon + 1, end, value);
  }

  private static void renameKey(
      final StringBuilder text, final int at, final SplittableRandom random) {
    final int quote = text.indexOf("\"", at);
    final int close = quote < 0 ? -1 : text.indexOf("\"", quote + 1);
    if (close > quote + 1) {
      final String[] names = {
        "type", "id", "operations", "effect", "selector", "scope", "Type", ""
      };
      text.replace(quote + 1, close, names[random.nextInt(names.length)]);
    }
  }

  // ── generation from the schema ────────────────────────────────────────────

  // Plausible values, so that many plans parse and reach the validators, and hostile ones, drawn
  // one time in twenty, so that every bound and every error path is exercised too.
  private static final String[] STRINGS = {"s", "db.internal", "10.0.0.0/8", "/var/lib/app"};
  private static final String[] HOSTILE_STRINGS = {
    "", " ", "::1", "*.internal", "PT1S", "\u0000", "x".repeat(300)
  };
  private static final String[] DURATIONS = {"PT0.001S", "PT0.05S", "PT5M"};
  private static final String[] HOSTILE_DURATIONS = {"PT0S", "PT-1S", "P400D", "nope"};
  private static final String[] INSTANTS = {"2026-11-01T08:00:00Z", "2030-01-01T00:00:00Z"};
  private static final String[] HOSTILE_INSTANTS = {
    "1970-01-01T00:00:00Z", "2026-02-30T00:00:00Z", "yesterday"
  };
  private static final long[] INTEGERS = {1, 3, 100, 4096};
  private static final long[] HOSTILE_INTEGERS = {0, -1, Integer.MAX_VALUE, Long.MIN_VALUE};
  private static final double[] NUMBERS = {0.05, 0.5, 1.0};
  private static final double[] HOSTILE_NUMBERS = {0.0, 1.5, -0.1, Double.MAX_VALUE, Double.NaN};

  private static boolean hostile(final SplittableRandom random) {
    return random.nextInt(20) == 0;
  }

  private static <T> T pick(final T[] plausible, final T[] hostile, final SplittableRandom random) {
    final T[] pool = hostile(random) ? hostile : plausible;
    return pool[random.nextInt(pool.length)];
  }

  private JsonNode generate(
      final JsonNode schema, final JsonNode node, final SplittableRandom random, final int depth) {
    final JsonNodeFactory nodes = JsonNodeFactory.instance;
    if (node.has("$ref")) {
      final String name = node.get("$ref").asText().substring("#/$defs/".length());
      return generate(schema, schema.get("$defs").get(name), random, depth + 1);
    }
    if (node.has("oneOf")) {
      final JsonNode choices = node.get("oneOf");
      return generate(schema, choices.get(random.nextInt(choices.size())), random, depth + 1);
    }
    if (node.has("const")) {
      return node.get("const");
    }
    if (node.has("enum")) {
      final JsonNode values = node.get("enum");
      return random.nextInt(40) == 0
          ? nodes.textNode("NOT_A_CONSTANT")
          : values.get(random.nextInt(values.size()));
    }
    final String type = node.path("type").asText();
    switch (type) {
      case "string" -> {
        final String format = node.path("format").asText();
        if (format.equals("duration")) {
          return nodes.textNode(pick(DURATIONS, HOSTILE_DURATIONS, random));
        }
        if (format.equals("date-time")) {
          return nodes.textNode(pick(INSTANTS, HOSTILE_INSTANTS, random));
        }
        return nodes.textNode(pick(STRINGS, HOSTILE_STRINGS, random));
      }
      case "integer" -> {
        final long[] pool = hostile(random) ? HOSTILE_INTEGERS : INTEGERS;
        return nodes.numberNode(pool[random.nextInt(pool.length)]);
      }
      case "number" -> {
        final double[] pool = hostile(random) ? HOSTILE_NUMBERS : NUMBERS;
        return nodes.numberNode(pool[random.nextInt(pool.length)]);
      }
      case "boolean" -> {
        return nodes.booleanNode(random.nextBoolean());
      }
      case "array" -> {
        final ArrayNode array = nodes.arrayNode();
        final int size = depth > 6 || !hostile(random) ? 1 + random.nextInt(2) : random.nextInt(4);
        for (int i = 0; i < size; i++) {
          array.add(generate(schema, node.get("items"), random, depth + 1));
        }
        return array;
      }
      default -> {
        final ObjectNode object = nodes.objectNode();
        final JsonNode properties = node.path("properties");
        final boolean typed = properties.has("type");
        final List<String> required = new ArrayList<>();
        node.path("required").forEach(name -> required.add(name.asText()));
        for (final Map.Entry<String, JsonNode> property : properties.properties()) {
          // A selector or an effect rejects many absent fields the schema leaves optional (an
          // empty operation set, a zero count): there a field is left out only on a hostile draw.
          // A policy's fields constrain each other, so there each is drawn one time in three.
          final boolean include =
              required.contains(property.getKey())
                  || (typed ? !hostile(random) : random.nextInt(3) == 0);
          if (include) {
            object.set(property.getKey(), generate(schema, property.getValue(), random, depth + 1));
          }
        }
        if (node.has("additionalProperties") && node.get("additionalProperties").isObject()) {
          for (int i = random.nextInt(3); i > 0; i--) {
            object.set(
                "k" + i, generate(schema, node.get("additionalProperties"), random, depth + 1));
          }
        }
        return object;
      }
    }
  }
}
