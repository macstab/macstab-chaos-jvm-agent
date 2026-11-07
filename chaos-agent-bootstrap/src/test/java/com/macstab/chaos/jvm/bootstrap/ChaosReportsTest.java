package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The status and hits files: switches, contents, and what happens when they cannot be written. */
@DisplayName("Chaos reports")
class ChaosReportsTest {

  @TempDir Path tempDir;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosReports> created = new ArrayList<>();

  @AfterEach
  void tearDown() {
    created.forEach(ChaosReports::close);
    runtime.close();
  }

  private ChaosReports create(final String agentArgs, final Map<String, String> environment) {
    final ChaosReports reports =
        ChaosReports.createIfEnabled(agentArgs, environment, runtime).orElseThrow();
    created.add(reports);
    return reports;
  }

  private Map<String, String> status(final Path directory) throws Exception {
    final Map<String, String> values = new java.util.LinkedHashMap<>();
    for (final String line : Files.readAllLines(directory.resolve(ChaosReports.STATUS_FILE))) {
      final int separator = line.indexOf('=');
      values.put(line.substring(0, separator), line.substring(separator + 1));
    }
    return values;
  }

  @Test
  @DisplayName("without reports=on, and with off or false, no reports are written")
  void switchedOff() {
    final Optional<ChaosReports> before = ChaosReports.current();

    assertThat(ChaosReports.createIfEnabled("", Map.of(), runtime)).as("off by default").isEmpty();
    assertThat(ChaosReports.createIfEnabled("reports=off", Map.of(), runtime)).isEmpty();
    assertThat(ChaosReports.createIfEnabled("reports=FALSE", Map.of(), runtime)).isEmpty();
    assertThat(
            ChaosReports.createIfEnabled(
                "", Map.of(ChaosReports.REPORTS_ENV, "off", "unrelated", "x"), runtime))
        .isEmpty();
    assertThat(ChaosReports.current()).as("nothing was published").isEqualTo(before);

    final ChaosReports enabled = create("reports=on;reportsDir=" + tempDir, Map.of());
    enabled.statusOk(1, "inline");
    assertThat(tempDir.resolve(ChaosReports.STATUS_FILE)).exists();
  }

  @Test
  @DisplayName("a blank report directory means the JVM's temp directory; the status can be removed")
  void blankDirectoryIsTempDirectory() throws Exception {
    final String tmp = System.getProperty("java.io.tmpdir");
    final ChaosReports reports;
    System.setProperty("java.io.tmpdir", tempDir.toString());
    try {
      reports = create("reports=on", Map.of(ChaosReports.REPORTS_DIR_ENV, "  "));
    } finally {
      System.setProperty("java.io.tmpdir", tmp);
    }

    reports.statusOk(2, "file:/plans/p.json");

    assertThat(status(tempDir))
        .containsEntry("state", "ok")
        .containsEntry("scenarios", "2")
        .containsEntry("source", "file:/plans/p.json");

    reports.removeStatus();
    assertThat(tempDir.resolve(ChaosReports.STATUS_FILE)).doesNotExist();
  }

  @Test
  @DisplayName("an error status flattens control characters, bounds long text and blanks a null")
  void errorStatusText() throws Exception {
    final ChaosReports reports = create("reports=on;reportsDir=" + tempDir, Map.of());

    reports.statusError(0, "x".repeat(300), 3, 7, null);
    final Map<String, String> missingReason = status(tempDir);
    assertThat(missingReason)
        .containsEntry("state", "error")
        .containsEntry("line", "3")
        .containsEntry("column", "7")
        .containsEntry("reason", "");
    assertThat(missingReason.get("source")).isEqualTo("x".repeat(200));

    reports.statusError(0, "inline", 1, 1, "bad\nreason\tbelow");
    assertThat(status(tempDir)).containsEntry("reason", "bad reason below");
  }

  @Test
  @DisplayName("reports takes the standard boolean literals: yes on, 0 off")
  void booleanLiterals() {
    create("reports=yes;reportsDir=" + tempDir, Map.of()).statusOk(1, "inline");
    assertThat(tempDir.resolve(ChaosReports.STATUS_FILE)).exists();
    assertThat(ChaosReports.createIfEnabled("reports=0", Map.of(), runtime)).isEmpty();
  }

  @Test
  @DisplayName("reports=true switches the files on like reports=on")
  void trueSwitchesOn() {
    final ChaosReports enabled = create("reports=true;reportsDir=" + tempDir, Map.of());
    enabled.statusOk(1, "inline");
    assertThat(tempDir.resolve(ChaosReports.STATUS_FILE)).exists();
  }

  @Test
  @DisplayName("a report directory that cannot be written is reported once, not on every write")
  void writeFailureLoggedOnce() throws Exception {
    final Path notADirectory = Files.writeString(tempDir.resolve("not-a-directory"), "x");
    final ChaosReports blocked = create("reports=on;reportsDir=" + notADirectory, Map.of());
    final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(ChaosReports.class.getName());
    final List<java.util.logging.LogRecord> warnings = new ArrayList<>();
    final java.util.logging.Handler capture =
        new java.util.logging.Handler() {
          @Override
          public void publish(final java.util.logging.LogRecord record) {
            if (record.getLevel() == java.util.logging.Level.WARNING) {
              warnings.add(record);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(capture);
    try {
      blocked.statusOk(1, "inline");
      blocked.statusOk(2, "inline");
    } finally {
      logger.removeHandler(capture);
    }

    assertThat(warnings).hasSize(1);
  }

  @Test
  @DisplayName("an unwritable report directory or status path leaves no file behind and no error")
  void unwritableReports() throws Exception {
    final Path notADirectory = Files.writeString(tempDir.resolve("plain-file"), "x");
    final ChaosReports blocked = create("reports=on;reportsDir=" + notADirectory, Map.of());
    blocked.statusOk(1, "inline");
    assertThat(Files.readString(notADirectory)).isEqualTo("x");

    // The status path is a non-empty directory: the staged file cannot replace it.
    final Path directory = Files.createDirectories(tempDir.resolve("reports"));
    final Path occupied = Files.createDirectories(directory.resolve(ChaosReports.STATUS_FILE));
    Files.writeString(occupied.resolve("keep"), "x");
    final ChaosReports reports = create("reports=on;reportsDir=" + directory, Map.of());
    reports.statusOk(1, "inline");
    reports.removeStatus();

    assertThat(occupied.resolve("keep")).exists();
    try (Stream<Path> files = Files.list(directory)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .as("the staged file is removed")
          .containsExactly(ChaosReports.STATUS_FILE);
    }
  }

  @Test
  @DisplayName("hits beyond the first 64 are batched and the final count is written on close")
  void hitsBeyondImmediateWrites() throws Throwable {
    runtime.activate(
        ChaosScenario.builder("gc-suppressed")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
            .effect(ChaosEffect.suppress())
            .activationPolicy(ActivationPolicy.always())
            .build());
    final ChaosReports reports = create("reports=on;reportsDir=" + tempDir, Map.of());

    for (int i = 0; i < 70; i++) {
      assertThat(runtime.beforeGcRequest()).as("suppressed").isTrue();
    }
    final Path hits =
        tempDir.resolve(ChaosReports.HITS_FILE_PREFIX + ProcessHandle.current().pid());
    // Events reach the listener asynchronously; the writer batches them every 100 ms.
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (!(Files.exists(hits) && Files.readAllLines(hits).contains("70\tgc-suppressed"))
        && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(Files.readAllLines(hits)).containsExactly("70\tgc-suppressed");

    Files.delete(hits);
    reports.close();
    assertThat(Files.readAllLines(hits))
        .as("written again on close")
        .containsExactly("70\tgc-suppressed");
  }

  @Test
  @DisplayName("closing a superseded instance keeps the current one; a second close does nothing")
  void closeKeepsNewerInstance() throws Exception {
    final ChaosReports older =
        create("reports=on;reportsDir=" + tempDir.resolve("older"), Map.of());
    final ChaosReports newer =
        create("reports=on;reportsDir=" + tempDir.resolve("newer"), Map.of());

    older.close();
    older.close();

    assertThat(ChaosReports.current()).containsSame(newer);
    newer.close();
    assertThat(ChaosReports.current()).isEmpty();
  }
}
