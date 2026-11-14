package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code reject()} on real calls of the non-I/O operations, in a child JVM. */
@DisplayName("Rejects of non-I/O operations on real calls")
class RuntimeRejectEndToEndTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("each call fails with what the real call raises, from the real call site")
  void rejectsRaiseTheRealFailure() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(RuntimeRejectProbeMain.class, "rejects");

    assertSoftly(
        softly -> {
          softly
              .assertThat(result.value("executorSubmit.type"))
              .isEqualTo("java.util.concurrent.RejectedExecutionException");
          softly
              .assertThat(result.value("executorSubmit.top"))
              .isEqualTo("java.util.concurrent.ThreadPoolExecutor.execute");
          softly
              .assertThat(result.value("virtualThreadStart.type"))
              .isEqualTo("java.util.concurrent.RejectedExecutionException");
          softly
              .assertThat(result.value("threadSleep.type"))
              .isEqualTo("java.lang.InterruptedException");
          softly.assertThat(result.value("threadSleep.top")).isEqualTo("java.lang.Thread.sleep");
          softly
              .assertThat(result.value("classDefine.type"))
              .isEqualTo("java.lang.ClassFormatError");
          softly
              .assertThat(result.value("classDefine.top"))
              .isEqualTo("java.lang.ClassLoader.defineClass");
          softly
              .assertThat(result.value("objectSerialize.type"))
              .isEqualTo("java.io.NotSerializableException");
          softly
              .assertThat(result.value("objectSerialize.top"))
              .isEqualTo("java.io.ObjectOutputStream.writeObject");
          softly
              .assertThat(result.value("objectDeserialize.type"))
              .isEqualTo("java.io.InvalidClassException");
          softly
              .assertThat(result.value("objectDeserialize.top"))
              .isEqualTo("java.io.ObjectInputStream.readObject");
          softly
              .assertThat(result.value("nativeLibraryLoad.type"))
              .isEqualTo("java.lang.UnsatisfiedLinkError");
          softly
              .assertThat(result.value("jndiLookup.type"))
              .isEqualTo("javax.naming.NamingException");
          softly
              .assertThat(result.value("jndiLookup.top"))
              .isEqualTo("javax.naming.InitialContext.lookup");
          softly
              .assertThat(result.value("jmxInvoke.type"))
              .isEqualTo("javax.management.MBeanException");
          softly.assertThat(result.value("processStart.type")).isEqualTo("java.io.IOException");
          softly
              .assertThat(result.value("processStart.top"))
              .isEqualTo("java.lang.ProcessBuilder.start");
          softly
              .assertThat(result.value("processWait.type"))
              .isEqualTo("java.lang.InterruptedException");
          softly
              .assertThat(result.value("methodExit.type"))
              .isEqualTo("java.lang.IllegalStateException");
          softly
              .assertThat(result.value("methodExit.top"))
              .isEqualTo("com.example.chaostarget.Greeter.count");
          softly
              .assertThat(result.value("jmxInvoke.cause"))
              .isEqualTo("java.lang.RuntimeException");
          // A reject of a lookup or completion answers with the value a failed call returns.
          softly.assertThat(result.value("resourceLoad.type")).isEqualTo("none");
          softly.assertThat(result.value("resourceLoad.value")).isEqualTo("null");
          softly.assertThat(result.value("asyncComplete.value")).isEqualTo("false/false");
          softly
              .assertThat(result.value("asyncCompleteExceptionally.value"))
              .isEqualTo("false/false");
        });
  }
}
