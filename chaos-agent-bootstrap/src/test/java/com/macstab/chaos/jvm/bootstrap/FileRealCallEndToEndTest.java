package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File effects on real files, in a child JVM. */
@DisplayName("File effects on real files")
class FileRealCallEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a reject or errno of every file operation fails the real call, which does nothing")
  void fileOperationsFail() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(FileRealCallProbeMain.class, "file-calls");

    assertSoftly(
        softly -> {
          softly.assertThat(result.value("read.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("read.message")).isEqualTo("chaos");
          softly.assertThat(result.value("sync.type")).isEqualTo("java.io.SyncFailedException");
          softly.assertThat(result.value("truncate.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("map.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("readEio.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("readEio.message")).isEqualTo("Input/output error");
          softly.assertThat(result.value("writeEdquot.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("writeEdquot.message")).isEqualTo("Disk quota exceeded");
          softly.assertThat(result.value("close.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("close.closed")).isEqualTo("java.io.IOException");
          softly
              .assertThat(result.value("openEnfile.type"))
              .isEqualTo("java.nio.file.FileSystemException");
          softly
              .assertThat(result.value("openEdquot.type"))
              .isEqualTo("java.nio.file.FileSystemException");
          softly.assertThat(result.value("move.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("move.sourceExists")).isEqualTo("true");
          softly.assertThat(result.value("moveTarget.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("moveTarget.targetExists")).isEqualTo("false");
          softly.assertThat(result.value("delete.type")).isEqualTo("java.io.IOException");
          softly.assertThat(result.value("delete.exists")).isEqualTo("true");
          softly.assertThat(result.value("fileDelete.type")).isEqualTo("none");
          softly.assertThat(result.value("fileDelete.value")).isEqualTo("false/true");
          softly
              .assertThat(result.value("deleteEio.type"))
              .isEqualTo("java.nio.file.FileSystemException");
          softly
              .assertThat(result.value("openEnfile.message"))
              .endsWith("/data: Too many open files in system");
          softly
              .assertThat(result.value("openEdquot.message"))
              .endsWith("/new: Disk quota exceeded");
          softly
              .assertThat(result.value("deleteEio.message"))
              .endsWith("/data: Input/output error");
          // FileDescriptor.sync() has no path, so a selector with a path pattern never matches it.
          softly.assertThat(result.value("streamSync.type")).isEqualTo("none");
        });
  }
}
