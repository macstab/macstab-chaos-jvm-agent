package com.macstab.chaos.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails when a module's public API differs from its committed record, and lists the difference per
 * type ({@link ApiSignatures}).
 */
public abstract class ApiCheckTask extends DefaultTask {

  /** The compiled classes of the module. */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getClasses();

  /** The committed record, {@code api/<module>.api}; absent for a module without one yet. */
  @InputFiles
  @PathSensitive(PathSensitivity.NONE)
  public abstract ConfigurableFileCollection getCommittedApi();

  /** The module's name, for the message. */
  @Input
  public abstract Property<String> getModuleName();

  /** Written when the check passes, so an unchanged module is not checked again. */
  @OutputFile
  public abstract RegularFileProperty getMarker();

  @TaskAction
  void check() throws IOException {
    final String module = getModuleName().get();
    final String actual = ApiSignatures.render(getClasses().get().getAsFile());
    String expected = "";
    for (final File committed : getCommittedApi().getFiles()) {
      if (committed.isFile()) {
        expected = Files.readString(committed.toPath(), StandardCharsets.UTF_8);
      }
    }
    if (!expected.equals(actual)) {
      throw new GradleException(
          "The public API of "
              + module
              + " differs from api/"
              + module
              + ".api. If intended, record it with ./gradlew :"
              + module
              + ":apiDump and review the diff.\n\n"
              + ApiSignatures.difference(expected, actual));
    }
    final File marker = getMarker().get().getAsFile();
    try {
      Files.createDirectories(marker.toPath().getParent());
      Files.writeString(marker.toPath(), "ok", StandardCharsets.UTF_8);
    } catch (final IOException unwritable) {
      throw new UncheckedIOException(unwritable);
    }
  }
}
