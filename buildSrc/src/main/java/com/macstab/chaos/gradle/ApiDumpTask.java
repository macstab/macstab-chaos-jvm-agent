package com.macstab.chaos.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Records the public API signatures of a module's classes ({@link ApiSignatures}). */
public abstract class ApiDumpTask extends DefaultTask {

  /** The compiled classes of the module. */
  @InputDirectory
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract DirectoryProperty getClasses();

  /** The committed record, {@code api/<module>.api}. */
  @OutputFile
  public abstract RegularFileProperty getApiFile();

  @TaskAction
  void dump() {
    final Path target = getApiFile().get().getAsFile().toPath();
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(
          target, ApiSignatures.render(getClasses().get().getAsFile()), StandardCharsets.UTF_8);
    } catch (final IOException unwritable) {
      throw new UncheckedIOException(unwritable);
    }
  }
}
