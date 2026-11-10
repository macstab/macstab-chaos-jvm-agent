package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Probe main for the L3a rows of {@link AgentEndToEndTest}: each mode activates a scenario on a
 * general primitive at run time, then exercises the JDK's own use of that primitive (which must not
 * be a target) and a use of the application's own (which must still be).
 */
public final class JdkOwnUseProbeMain {

  /** Loaded only from a copy of its class file, by the class-loading rows. */
  public static final class Loaded {
    private Loaded() {}
  }

  private static final String LOADED = "com.macstab.chaos.jvm.bootstrap.JdkOwnUseProbeMain$Loaded";

  private JdkOwnUseProbeMain() {}

  /**
   * Runs the mode named by {@code args[0]}.
   *
   * @param args the mode
   * @throws Exception on a probe failure
   */
  public static void main(final String[] args) throws Exception {
    // Loads the probe's own helper types before a scenario is active.
    print("warm", outcome(() -> "up"));
    switch (args[0]) {
      case "file-read-reject" -> fileReadReject();
      case "zip-inflate-reject" -> zipInflateReject();
      case "nio-select-reject" -> nioSelectReject();
      case "async-suppress-or-timeout" ->
          orTimeoutUnder(
              ChaosSelector.async(
                  Set.of(OperationType.ASYNC_COMPLETE, OperationType.ASYNC_COMPLETE_EXCEPTIONALLY)),
              ChaosEffect.suppress());
      case "schedule-suppress-or-timeout" ->
          orTimeoutUnder(
              ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)),
              ChaosEffect.suppress());
      case "schedule-tick-suppress-or-timeout" ->
          orTimeoutUnder(
              ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_TICK)),
              ChaosEffect.suppress());
      case "delayed-executor" -> delayedExecutor();
      case "timeout-marker-cleared" -> timeoutMarkerCleared();
      case "jmx-reject" -> jmxReject();
      case "thread-local-suppress" -> threadLocalSuppress();
      case "reflection-reject-serialization" -> reflectionRejectSerialization();
      case "reflection-reject-jdk-callers" -> reflectionRejectJdkCallers();
      case "weave-under-reflection-reject" -> weaveUnderReflectionReject();
      case "direct-buffer-reject-socket-read" -> directBufferRejectSocketRead();
      case "gc-suppress-bits" -> gcSuppressBits();
      case "woven-after-file-io" ->
          wovenAfter(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)));
      case "woven-after-thread-local" ->
          wovenAfter(ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET)));
      default -> throw new IllegalArgumentException(args[0]);
    }
  }

  private static ChaosActivationHandle activate(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosPlatform.current()
        .activate(
            ChaosScenario.builder(id)
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(selector)
                .effect(effect)
                .activationPolicy(ActivationPolicy.always())
                .build());
  }

  private static void print(final String key, final Object value) {
    System.out.println(key + "=" + value);
  }

  private static String outcome(final ThrowingAction action) {
    try {
      return String.valueOf(action.run());
    } catch (final Throwable failure) {
      return failure.getClass().getName();
    }
  }

  /** An action that returns what it observed, or throws. */
  private interface ThrowingAction {
    Object run() throws Throwable;
  }

  /** The class file of {@link Loaded}, read before any scenario is active. */
  private static byte[] loadedClassFile() throws IOException {
    try (InputStream in =
        JdkOwnUseProbeMain.class
            .getClassLoader()
            .getResourceAsStream(LOADED.replace('.', '/') + ".class")) {
      return in.readAllBytes();
    }
  }

  /**
   * A FILE_IO_READ reject with no path pattern: a class loads from a class directory and {@code
   * UUID.randomUUID()} reads {@code /dev/urandom}; a read of the application's own file fails.
   */
  private static void fileReadReject() throws Exception {
    final Path dir = Files.createTempDirectory("own-use");
    final Path classFile = dir.resolve(LOADED.replace('.', '/') + ".class");
    Files.createDirectories(classFile.getParent());
    Files.write(classFile, loadedClassFile());
    final Path userFile = Files.writeString(dir.resolve("user.txt"), "user");
    final URL[] classPath = {dir.toUri().toURL()};
    try (ChaosActivationHandle handle =
        activate(
            "read",
            ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)),
            ChaosEffect.reject("disk"))) {
      try (URLClassLoader loader = new URLClassLoader(classPath, null)) {
        print(
            "classLoaded",
            outcome(() -> Class.forName(LOADED, true, loader).getClassLoader() == loader));
      }
      print("uuid", outcome(() -> UUID.randomUUID().toString().length()));
      print("userRead", outcome(() -> Files.readAllBytes(userFile).length));
      print("matched", handle.matchedCount());
    }
  }

  /**
   * A ZIP_INFLATE reject: a class loads from a jar whose entry is deflated; a GZIP stream of the
   * application's own fails.
   */
  private static void zipInflateReject() throws Exception {
    final Path jar = Files.createTempFile("own-use", ".jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      final JarEntry entry = new JarEntry(LOADED.replace('.', '/') + ".class");
      entry.setMethod(JarEntry.DEFLATED);
      out.putNextEntry(entry);
      out.write(loadedClassFile());
      out.closeEntry();
    }
    final ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
    try (OutputStream gzip = new GZIPOutputStream(gzipped)) {
      gzip.write("user".getBytes(StandardCharsets.UTF_8));
    }
    final URL[] classPath = {jar.toUri().toURL()};
    try (ChaosActivationHandle handle =
        activate(
            "inflate",
            ChaosSelector.jvmRuntime(Set.of(OperationType.ZIP_INFLATE)),
            ChaosEffect.reject("corrupt"))) {
      try (URLClassLoader loader = new URLClassLoader(classPath, null)) {
        print(
            "classLoaded",
            outcome(() -> Class.forName(LOADED, true, loader).getClassLoader() == loader));
      }
      print(
          "gzip",
          outcome(
              () ->
                  new GZIPInputStream(new ByteArrayInputStream(gzipped.toByteArray()))
                      .readAllBytes()
                      .length));
      print("matched", handle.matchedCount());
    }
  }

  /**
   * A NIO_SELECTOR_SELECT reject: the JDK HttpClient's selector thread keeps working; a selector of
   * the application's own fails.
   */
  private static void nioSelectReject() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      final Thread responder =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    try (Socket socket = server.accept()) {
                      final InputStream in = socket.getInputStream();
                      int matched = 0;
                      final byte[] end = {'\r', '\n', '\r', '\n'};
                      while (matched < end.length) {
                        final int read = in.read();
                        if (read < 0) {
                          return;
                        }
                        matched = read == end[matched] ? matched + 1 : (read == '\r' ? 1 : 0);
                      }
                      socket
                          .getOutputStream()
                          .write(
                              ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                                  .getBytes(StandardCharsets.US_ASCII));
                    } catch (final IOException ignored) {
                      // the client gave up
                    }
                  });
      final URI uri = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/");
      try (ChaosActivationHandle handle =
          activate(
              "select",
              ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)),
              ChaosEffect.reject("select"))) {
        final HttpClient client =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        print(
            "httpClient",
            outcome(
                () ->
                    client
                        .sendAsync(
                            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString())
                        .get(10, TimeUnit.SECONDS)
                        .statusCode()));
        print(
            "userSelect",
            outcome(
                () -> {
                  try (Selector selector = Selector.open()) {
                    return selector.selectNow();
                  }
                }));
        print("matched", handle.matchedCount());
      }
      responder.join(1_000L);
    }
  }

  /** {@code orTimeout(50 ms)} on a future nothing completes, under {@code selector}. */
  private static void orTimeoutUnder(final ChaosSelector selector, final ChaosEffect effect)
      throws Exception {
    try (ChaosActivationHandle handle = activate("timer", selector, effect)) {
      final CompletableFuture<String> timed =
          new CompletableFuture<String>().orTimeout(50, TimeUnit.MILLISECONDS);
      print("orTimeout", timeoutOutcome(timed));
      print("matched", handle.matchedCount());
    }
  }

  private static String timeoutOutcome(final CompletableFuture<?> future) throws Exception {
    try {
      return String.valueOf(future.get(3, TimeUnit.SECONDS));
    } catch (final ExecutionException failed) {
      return failed.getCause().getClass().getName();
    } catch (final TimeoutException hung) {
      return "hung";
    }
  }

  /**
   * {@code delayedExecutor} stays evaluated while the JDK's {@code orTimeout} timer is not: on 21
   * through SCHEDULE_SUBMIT, on 25 through the FORK_JOIN_TASK_RUN of its {@code
   * ScheduledForkJoinTask}.
   */
  private static void delayedExecutor() throws Exception {
    try (ChaosActivationHandle scheduled =
            activate(
                "sched",
                ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT)),
                ChaosEffect.delay(Duration.ofMillis(1)));
        ChaosActivationHandle forkJoin =
            activate(
                "fj",
                new ChaosSelector.ExecutorSelector(
                    Set.of(OperationType.FORK_JOIN_TASK_RUN),
                    NamePattern.any(),
                    NamePattern.exact("java.util.concurrent.DelayScheduler$ScheduledForkJoinTask"),
                    null),
                ChaosEffect.delay(Duration.ofMillis(1)))) {
      print(
          "orTimeout",
          timeoutOutcome(new CompletableFuture<String>().orTimeout(20, TimeUnit.MILLISECONDS)));
      final CountDownLatch ran = new CountDownLatch(1);
      CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS).execute(ran::countDown);
      print("delayedRan", ran.await(3, TimeUnit.SECONDS));
      print("matched.sched", scheduled.matchedCount());
      print("matched.fj", forkJoin.matchedCount());
    }
  }

  /**
   * A JDK timer that runs on an already-completed future (it completes nothing) leaves no marker
   * behind: the next completion on the same thread is the application's and is evaluated. Needs
   * {@code --add-opens java.base/java.util.concurrent=ALL-UNNAMED} to build the timer.
   */
  private static void timeoutMarkerCleared() throws Exception {
    final java.lang.reflect.Constructor<?> constructor =
        Class.forName("java.util.concurrent.CompletableFuture$Timeout")
            .getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    final CompletableFuture<String> done = CompletableFuture.completedFuture("done");
    final Runnable timer =
        (Runnable)
            (constructor.getParameterCount() == 1
                ? constructor.newInstance(done)
                : constructor.newInstance(done, null, true));
    try (ChaosActivationHandle handle =
        activate(
            "complete",
            ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE)),
            ChaosEffect.delay(Duration.ofMillis(1)))) {
      timer.run();
      print("userComplete", new CompletableFuture<String>().complete("user"));
      print("matched", handle.matchedCount());
    }
  }

  /** A JMX reject: the agent's own MBean still answers; the platform's MBeans do not. */
  private static void jmxReject() throws Exception {
    final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    final ObjectName agent = new ObjectName("com.macstab.chaos.jvm:type=ChaosDiagnostics");
    final ObjectName runtime = new ObjectName("java.lang:type=Runtime");
    try (ChaosActivationHandle handle =
        activate(
            "jmx",
            ChaosSelector.jvmRuntime(Set.of(OperationType.JMX_INVOKE, OperationType.JMX_GET_ATTR)),
            ChaosEffect.reject("jmx"))) {
      print(
          "agentInvoke",
          outcome(() -> server.invoke(agent, "debugDump", new Object[0], new String[0]) != null));
      print("userGetAttribute", outcome(() -> server.getAttribute(runtime, "Uptime") != null));
      print(
          "userGetAttributes",
          outcome(() -> server.getAttributes(runtime, new String[] {"Uptime"}).size()));
      print("matched", handle.matchedCount());
    }
  }

  /**
   * A THREAD_LOCAL_GET suppress: a {@code ReentrantReadWriteLock} read by two threads keeps its
   * hold counts; the application's ThreadLocals, {@code withInitial} ones included, read null.
   */
  private static void threadLocalSuppress() throws Exception {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    final ThreadLocal<String> plain = new ThreadLocal<>();
    plain.set("plain");
    final ThreadLocal<String> withInitial = ThreadLocal.withInitial(() -> "initial");
    try (ChaosActivationHandle handle =
        activate(
            "tl",
            ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET)),
            ChaosEffect.suppress())) {
      final AtomicReference<String> second = new AtomicReference<>();
      lock.readLock().lock();
      try {
        Thread.ofPlatform()
            .start(
                () ->
                    second.set(
                        outcome(
                            () -> {
                              lock.readLock().lock();
                              try {
                                return "ok";
                              } finally {
                                lock.readLock().unlock();
                              }
                            })))
            .join();
      } finally {
        lock.readLock().unlock();
      }
      print("rrwl", second.get());
      print("plain", plain.get());
      print("withInitial", withInitial.get());
      print("matched", handle.matchedCount());
    }
  }

  /**
   * A REFLECTION_INVOKE reject: serialization still calls a class's own {@code writeObject}; the
   * application's {@code Method.invoke} fails.
   */
  private static void reflectionRejectSerialization() throws Exception {
    try (ChaosActivationHandle handle =
        activate(
            "reflect",
            ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)),
            ChaosEffect.reject("reflect"))) {
      print(
          "serialize",
          outcome(
              () -> {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                  out.writeObject(new ArrayList<>(List.of("a", "b")));
                }
                return bytes.size() > 0;
              }));
      print(
          "userInvoke",
          outcome(() -> Object.class.getMethod("hashCode").invoke(new Object()) != null));
      print("matched", handle.matchedCount());
    }
  }

  /** An application enum: {@code EnumSet} reads its constants through {@code Method.invoke}. */
  private enum Colour {
    RED,
    GREEN
  }

  /**
   * A REFLECTION_INVOKE reject: the JDK's own {@code Method.invoke} calls (an {@code EnumSet}'s
   * universe, the TLS provider's set-up behind {@code SSLContext}) are not targets; the
   * application's own {@code Method.invoke} fails.
   */
  private static void reflectionRejectJdkCallers() throws Exception {
    try (ChaosActivationHandle handle =
        activate(
            "reflect",
            ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)),
            ChaosEffect.reject("reflect"))) {
      print("enumSet", outcome(() -> java.util.EnumSet.allOf(Colour.class).size()));
      print(
          "sslContext",
          outcome(
              () -> {
                final javax.net.ssl.SSLContext context =
                    javax.net.ssl.SSLContext.getInstance("TLS");
                context.init(null, null, null);
                return context.createSSLEngine().getEnabledProtocols().length > 0;
              }));
      print(
          "userInvoke",
          outcome(() -> Object.class.getMethod("hashCode").invoke(new Object()) != null));
      print("matched", handle.matchedCount());
    }
  }

  /**
   * A REFLECTION_INVOKE reject does not reach the agent's own weaving (L3): a class loaded while it
   * is active still gets the method hook of a scenario activated before, and a hook group woven
   * while it is active still applies.
   */
  private static void weaveUnderReflectionReject() throws Exception {
    try (ChaosActivationHandle zero =
            activate(
                "zero-late-count",
                new ChaosSelector.MethodSelector(
                    Set.of(OperationType.METHOD_EXIT),
                    NamePattern.exact("com.example.chaostarget.LateCounter"),
                    NamePattern.exact("count"),
                    null),
                ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.ZERO));
        ChaosActivationHandle reflect =
            activate(
                "reflect",
                ChaosSelector.jvmRuntime(Set.of(OperationType.REFLECTION_INVOKE)),
                ChaosEffect.reject("reflect"))) {
      print("lateCount", outcome(() -> new com.example.chaostarget.LateCounter().count()));
      print(
          "groupWoven",
          outcome(
              () -> {
                try (ChaosActivationHandle offer =
                    activate(
                        "offer",
                        ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)),
                        ChaosEffect.reject("full"))) {
                  return new java.util.concurrent.ArrayBlockingQueue<String>(1).offer("x");
                }
              }));
      print(
          "hookFailures",
          ChaosPlatform.current().diagnostics().snapshot().runtimeDetails().get("hookFailures"));
      print("reflectionMatched", reflect.matchedCount());
      print("zeroMatched", zero.matchedCount());
    }
  }

  /**
   * A DIRECT_BUFFER_ALLOCATE reject (JDK 21): a socket read, whose temporary direct buffer the JDK
   * allocates, still reads; the application's {@code allocateDirect} fails.
   */
  private static void directBufferRejectSocketRead() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket accepted = server.accept()) {
      accepted.getOutputStream().write(new byte[] {1, 2, 3, 4});
      try (ChaosActivationHandle handle =
          activate(
              "direct",
              ChaosSelector.jvmRuntime(Set.of(OperationType.DIRECT_BUFFER_ALLOCATE)),
              ChaosEffect.reject("direct"))) {
        final AtomicReference<String> read = new AtomicReference<>();
        // A new thread: its temporary-buffer cache is empty, so the read allocates one.
        Thread.ofPlatform()
            .start(() -> read.set(outcome(() -> client.getInputStream().readNBytes(4).length)))
            .join();
        print("socketRead", read.get());
        print("userAllocate", outcome(() -> ByteBuffer.allocateDirect(16).capacity()));
        print("matched", handle.matchedCount());
      }
    }
  }

  /**
   * A SYSTEM_GC_REQUEST suppress (with {@code -XX:MaxDirectMemorySize=16m}): direct buffers beyond
   * the limit are still freed by the collection {@code Bits.reserveMemory} requests; the
   * application's {@code System.gc()} is evaluated.
   */
  private static void gcSuppressBits() throws Exception {
    try (ChaosActivationHandle handle =
        activate(
            "gc",
            ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)),
            ChaosEffect.suppress())) {
      print(
          "allocations",
          outcome(
              () -> {
                long total = 0;
                for (int i = 0; i < 64; i++) {
                  total += ByteBuffer.allocateDirect(1024 * 1024).capacity();
                }
                return total / (1024 * 1024);
              }));
      System.gc();
      print("matched", handle.matchedCount());
    }
  }

  /** Activates a scenario for {@code selector} and prints the woven groups and the hooks. */
  private static void wovenAfter(final ChaosSelector selector) {
    try (ChaosActivationHandle handle =
        activate("woven", selector, ChaosEffect.delay(Duration.ofMillis(1)))) {
      print(
          "woven",
          ChaosPlatform.current().diagnostics().snapshot().runtimeDetails().get("wovenHookGroups"));
      print(
          "matchedHooks",
          String.join(
              "|",
              com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.matchedHooks()));
    }
  }
}
