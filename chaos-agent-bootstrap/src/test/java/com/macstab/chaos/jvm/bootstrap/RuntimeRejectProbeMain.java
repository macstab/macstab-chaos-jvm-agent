package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.ObjectName;

/**
 * Probe main for {@code reject()} on real calls of the non-I/O operations: each row prints what the
 * caller saw ({@link RealCallRows#row}), or the value the call returned where a reject answers with
 * a value.
 */
public final class RuntimeRejectProbeMain {

  private static final ChaosEffect REJECT = ChaosEffect.reject("chaos");

  private RuntimeRejectProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "rejects" -> rejects();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /** A standard MBean of the application. */
  public interface ProbeMBean {
    /**
     * An operation.
     *
     * @return 1
     */
    int ping();
  }

  /** The MBean's implementation. */
  public static final class Probe implements ProbeMBean {
    @Override
    public int ping() {
      return 1;
    }
  }

  /** A class loader that defines one class from its bytes. */
  private static final class Definer extends ClassLoader {
    Definer() {
      super(null);
    }

    Class<?> define(final String name, final byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  private static void rejects() throws Exception {
    final ExecutorService pool = Executors.newFixedThreadPool(1);
    RealCallRows.row(
        "executorSubmit",
        ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)),
        REJECT,
        () -> pool.submit(() -> {}));
    pool.shutdownNow();
    RealCallRows.row(
        "virtualThreadStart",
        ChaosSelector.thread(
            Set.of(OperationType.VIRTUAL_THREAD_START), ChaosSelector.ThreadKind.VIRTUAL),
        REJECT,
        () -> Thread.ofVirtual().start(() -> {}).join());
    RealCallRows.row(
        "threadSleep",
        ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY),
        REJECT,
        () -> Thread.sleep(1));
    final byte[] lateCounter;
    try (InputStream in =
        RuntimeRejectProbeMain.class.getResourceAsStream(
            "/com/example/chaostarget/LateCounter.class")) {
      lateCounter = in.readAllBytes();
    }
    RealCallRows.row(
        "classDefine",
        ChaosSelector.classLoading(
            Set.of(OperationType.CLASS_DEFINE),
            NamePattern.exact("com.example.chaostarget.LateCounter")),
        REJECT,
        () -> new Definer().define("com.example.chaostarget.LateCounter", lateCounter));
    final AtomicReference<Object> resource = new AtomicReference<>("unset");
    RealCallRows.row(
        "resourceLoad",
        ChaosSelector.classLoading(
            Set.of(OperationType.RESOURCE_LOAD),
            NamePattern.exact("com/example/chaostarget/LateCounter.class")),
        REJECT,
        () ->
            resource.set(
                RuntimeRejectProbeMain.class
                    .getClassLoader()
                    .getResource("com/example/chaostarget/LateCounter.class")));
    System.out.println("resourceLoad.value=" + resource.get());
    RealCallRows.row(
        "objectSerialize",
        ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_SERIALIZE)),
        REJECT,
        () -> new ObjectOutputStream(new ByteArrayOutputStream()).writeObject("payload"));
    final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(serialized)) {
      out.writeObject("payload");
    }
    RealCallRows.row(
        "objectDeserialize",
        ChaosSelector.jvmRuntime(Set.of(OperationType.OBJECT_DESERIALIZE)),
        REJECT,
        () ->
            new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray())).readObject());
    RealCallRows.row(
        "nativeLibraryLoad",
        ChaosSelector.jvmRuntime(Set.of(OperationType.NATIVE_LIBRARY_LOAD)),
        REJECT,
        () -> System.loadLibrary("net"));
    RealCallRows.row(
        "jndiLookup",
        ChaosSelector.jvmRuntime(Set.of(OperationType.JNDI_LOOKUP)),
        REJECT,
        () -> new javax.naming.InitialContext().lookup("java:comp/env/probe"));
    final ObjectName name = new ObjectName("com.example.probe:type=Probe");
    ManagementFactory.getPlatformMBeanServer().registerMBean(new Probe(), name);
    RealCallRows.row(
        "jmxInvoke",
        ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_INVOKE)),
        REJECT,
        () ->
            ManagementFactory.getPlatformMBeanServer()
                .invoke(name, "ping", new Object[0], new String[0]));
    RealCallRows.row(
        "processStart",
        ChaosSelector.process(NamePattern.exact("true")),
        REJECT,
        () -> new ProcessBuilder("true").start().waitFor());
    final Process process = new ProcessBuilder("true").start();
    RealCallRows.row(
        "processWait",
        new ChaosSelector.ProcessSelector(Set.of(OperationType.PROCESS_WAIT), NamePattern.any()),
        REJECT,
        process::waitFor);
    process.waitFor();
    final CompletableFuture<String> completed = new CompletableFuture<>();
    final AtomicReference<Object> completion = new AtomicReference<>("unset");
    RealCallRows.row(
        "asyncComplete",
        ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
        REJECT,
        () -> completion.set(completed.complete("value")));
    System.out.println("asyncComplete.value=" + completion.get() + "/" + completed.isDone());
    final CompletableFuture<String> failed = new CompletableFuture<>();
    RealCallRows.row(
        "asyncCompleteExceptionally",
        ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE_EXCEPTIONALLY)),
        REJECT,
        () -> completion.set(failed.completeExceptionally(new IllegalStateException("x"))));
    System.out.println(
        "asyncCompleteExceptionally.value=" + completion.get() + "/" + failed.isDone());
    RealCallRows.row(
        "methodExit",
        new ChaosSelector.MethodSelector(
            Set.of(OperationType.METHOD_EXIT),
            NamePattern.exact("com.example.chaostarget.Greeter"),
            NamePattern.exact("count"),
            null),
        REJECT,
        () -> new com.example.chaostarget.Greeter().count());
  }
}
