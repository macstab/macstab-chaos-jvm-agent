package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import com.macstab.chaos.jvm.api.OperationType;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Probe main for {@link RealPathIntegrationTest}: each mode drives woven JDK calls (mostly {@code
 * Thread.sleep}) under one effect, policy or stressor in a JVM with the agent, and prints what it
 * observed as {@code key=value} lines. The probe's own waits use {@link CountDownLatch#await} (a
 * park), never {@code Thread.sleep}, so a {@code THREAD_SLEEP} scenario never reaches them.
 */
public final class RealPathProbeMain {

  private RealPathProbeMain() {}

  public static void main(final String[] programArgs) throws Exception {
    // The mode and its arguments: "policy:probability:42", or separate program arguments.
    final String[] args = programArgs.length == 1 ? programArgs[0].split(":", -1) : programArgs;
    if (args[0].equals("exit-now")) {
      // A startup plan's non-daemon keep-alive thread alone keeps this JVM running.
      System.out.println("mainReturned=true");
      return;
    }
    final ChaosControlPlane plane = ChaosPlatform.installLocally();
    switch (args[0]) {
      case "timed-sleep" -> timedStartupSleep(plane, args[1]);
      case "reload" -> reload(plane, java.nio.file.Path.of(args[1]), args[2]);
      case "gate-release" -> gate(plane, "release");
      case "gate-max-block" -> gate(plane, "max-block");
      case "gate-stop" -> gate(plane, "stop");
      case "policy" -> policy(plane, args[1], args.length > 2 ? args[2] : "");
      case "clock-mode" -> clockMode(plane, ChaosEffect.ClockSkewMode.valueOf(args[1]));
      case "direct-buffer-oom" -> directBufferOom(plane);
      case "heap-pressure" -> heapPressure(plane);
      case "metaspace-pressure" -> metaspacePressure(plane);
      case "code-cache-pressure" -> codeCachePressure(plane);
      case "string-intern-pressure" -> stringInternPressure(plane);
      default -> throw new IllegalArgumentException("unknown mode " + args[0]);
    }
  }

  /** Waits {@code millis} by parking, never by a woven {@code Thread.sleep}. */
  private static void pause(final long millis) throws InterruptedException {
    new CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS);
  }

  private static ChaosSelector sleeps() {
    return ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY);
  }

  // ── startup plans ─────────────────────────────────────────────────────────────────────────────

  /**
   * Times one {@code Thread.sleep(1)} under the startup plan and prints the scenario's counters.
   */
  private static void timedStartupSleep(final ChaosControlPlane plane, final String id)
      throws InterruptedException {
    System.out.println("sleepMillis=" + timedSleep());
    plane
        .diagnostics()
        .scenario(id)
        .ifPresentOrElse(
            report -> {
              System.out.println("state=" + report.state());
              System.out.println("applied=" + report.appliedCount());
            },
            () -> System.out.println("state=ABSENT"));
  }

  /**
   * Times a sleep under the watched startup plan, rewrites the plan without the scenario {@code
   * id}, waits for the reload to stop it, and times a sleep again.
   */
  private static void reload(
      final ChaosControlPlane plane, final java.nio.file.Path planFile, final String id)
      throws Exception {
    System.out.println("beforeMillis=" + timedSleep());
    java.nio.file.Files.writeString(
        planFile,
        com.macstab.chaos.jvm.startup.ChaosPlanMapper.write(
            new com.macstab.chaos.jvm.api.ChaosPlan(
                new com.macstab.chaos.jvm.api.ChaosPlan.Metadata("reloaded", ""),
                null,
                List.of(
                    ChaosScenario.builder("unrelated")
                        .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
                        .effect(ChaosEffect.suppress())
                        .activationPolicy(ActivationPolicy.always())
                        .build()))));
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (plane.diagnostics().scenario(id).isPresent()
        && plane.diagnostics().scenario(id).orElseThrow().state()
            == com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioState.ACTIVE
        && System.nanoTime() - deadline < 0) {
      pause(20L);
    }
    System.out.println(
        "removedState="
            + plane
                .diagnostics()
                .scenario(id)
                .map(report -> report.state().name())
                .orElse("ABSENT"));
    System.out.println(
        "unrelatedState="
            + plane
                .diagnostics()
                .scenario("unrelated")
                .map(report -> report.state().name())
                .orElse("ABSENT"));
    System.out.println("afterMillis=" + timedSleep());
  }

  // ── gate ──────────────────────────────────────────────────────────────────────────────────────

  /**
   * A gate on {@code Thread.sleep}: a worker's {@code Thread.sleep(1)} is held until the gate is
   * released, its {@code maxBlock} passes, or the scenario is stopped.
   */
  private static void gate(final ChaosControlPlane plane, final String how) throws Exception {
    final Duration maxBlock =
        how.equals("max-block") ? Duration.ofMillis(200) : Duration.ofMinutes(1);
    final ChaosActivationHandle gate =
        plane.activate(
            ChaosScenario.builder("gate")
                .selector(sleeps())
                .effect(ChaosEffect.gate(maxBlock))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final CountDownLatch returned = new CountDownLatch(1);
    final long[] elapsed = new long[1];
    final long[] returnedAt = new long[1];
    final Thread worker =
        new Thread(
            () -> {
              final long start = System.nanoTime();
              try {
                Thread.sleep(1L);
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              returnedAt[0] = System.nanoTime();
              elapsed[0] = (returnedAt[0] - start) / 1_000_000L;
              returned.countDown();
            },
            "gated-worker");
    worker.start();
    if (how.equals("max-block")) {
      System.out.println("returnedBeforeMaxBlock=" + returned.await(150, TimeUnit.MILLISECONDS));
      System.out.println("returned=" + returned.await(10, TimeUnit.SECONDS));
    } else {
      System.out.println("heldAfter300ms=" + !returned.await(300, TimeUnit.MILLISECONDS));
      final long released = System.nanoTime();
      if (how.equals("release")) {
        gate.release();
      } else {
        gate.stop();
      }
      System.out.println("returned=" + returned.await(10, TimeUnit.SECONDS));
      System.out.println(
          "returnedAfterReleaseMillis=" + (System.nanoTime() - released) / 1_000_000L);
      // Measured on one clock: the worker starts its own only once it runs, which a loaded
      // runner delays past the start of the 300 ms wait.
      System.out.println("returnedAfterRelease=" + (returnedAt[0] - released >= 0));
    }
    worker.join(10_000L);
    System.out.println("elapsedMillis=" + elapsed[0]);
    System.out.println("applied=" + gate.appliedCount());
    gate.close();
  }

  // ── activation policies ───────────────────────────────────────────────────────────────────────

  /**
   * Suppresses {@code Thread.sleep(1)} under one activation policy and makes real calls; prints the
   * scenario's counters and the match numbers it applied to, in order.
   */
  private static void policy(final ChaosControlPlane plane, final String policy, final String arg)
      throws Exception {
    final List<Long> appliedMatches = new CopyOnWriteArrayList<>();
    plane.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.APPLIED && "policy".equals(event.scenarioId())) {
            appliedMatches.add(Long.parseLong(event.attributes().get("match")));
          }
        });
    final ActivationPolicy.Builder builder = ActivationPolicy.builder();
    ChaosEffect effect = ChaosEffect.suppress();
    int calls = 0;
    final List<String> extra = new ArrayList<>();
    switch (policy) {
      case "probability" -> {
        builder.probability(0.3d);
        if (!arg.isEmpty()) {
          builder.randomSeed(Long.parseLong(arg));
        }
        calls = 1_000;
      }
      case "replay" -> {
        builder.replayMatches(
            java.util.Arrays.stream(arg.split(",")).map(Long::parseLong).toList());
        calls = 50;
      }
      case "warm-up" -> {
        builder.activateAfterMatches(10L);
        calls = 15;
      }
      case "rate-limit", "active-from", "active-for", "active-until" -> {
        // driven below
      }
      default -> throw new IllegalArgumentException(policy);
    }
    final Instant now = Instant.now();
    switch (policy) {
      case "rate-limit" -> builder.rateLimit(5L, Duration.ofSeconds(1));
      // Wide enough for the activation's weaving and the calls before the window.
      case "active-from" -> builder.activeFrom(now.plusMillis(3_000));
      case "active-for" -> {
        // Wide enough for the activation's weaving and the first (delayed) call.
        builder.activeFor(Duration.ofSeconds(1));
        effect = ChaosEffect.delay(Duration.ofMillis(200));
      }
      case "active-until" -> {
        // Wide enough for the activation's weaving, which the window includes.
        builder.activeUntil(now.plusMillis(3_000));
        effect = ChaosEffect.delay(Duration.ofMillis(200));
      }
      default -> {
        // set above
      }
    }
    final ChaosActivationHandle handle =
        plane.activate(
            ChaosScenario.builder("policy")
                .selector(sleeps())
                .effect(effect)
                .activationPolicy(builder.build())
                .build());
    switch (policy) {
      case "rate-limit" -> {
        // 20 one-millisecond sleeps fit the first 1 s window even on a slow runner.
        sleepCalls(20);
        extra.add("appliedFirstWindow=" + handle.appliedCount());
        pause(1_100L);
        sleepCalls(10);
      }
      case "active-from" -> {
        sleepCalls(10);
        extra.add("matchedBeforeFrom=" + handle.matchedCount());
        extra.add("appliedBeforeFrom=" + handle.appliedCount());
        pause(
            Math.max(0L, Duration.between(Instant.now(), now.plusMillis(3_000)).toMillis()) + 300L);
        sleepCalls(10);
      }
      case "active-for", "active-until" -> {
        extra.add("firstCallMillis=" + timedSleep());
        final ActivationPolicy bounds = builder.build();
        pause(
            bounds.activeUntil() != null
                ? Math.max(
                        0L,
                        java.time.Duration.between(Instant.now(), bounds.activeUntil()).toMillis())
                    + 300L
                : 1_300L);
        extra.add("lateCallMillis=" + timedSleep());
        extra.add("state=" + handle.state());
      }
      default -> sleepCalls(calls);
    }
    extra.forEach(System.out::println);
    System.out.println("matched=" + handle.matchedCount());
    System.out.println("applied=" + handle.appliedCount());
    System.out.println(
        "appliedMatches="
            + appliedMatches.stream().map(String::valueOf).collect(Collectors.joining(",")));
    handle.close();
  }

  private static void sleepCalls(final int calls) throws InterruptedException {
    for (int call = 0; call < calls; call++) {
      Thread.sleep(1L);
    }
  }

  private static long timedSleep() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1L);
    return (System.nanoTime() - start) / 1_000_000L;
  }

  // ── clock skew ────────────────────────────────────────────────────────────────────────────────

  /**
   * Skews {@code Instant.now()} in {@code mode} and prints each of three reads' offset from the
   * real time ({@code System.currentTimeMillis()}, which the scenario does not select), 200 ms
   * apart.
   */
  private static void clockMode(final ChaosControlPlane plane, final ChaosEffect.ClockSkewMode mode)
      throws Exception {
    final Duration amount =
        mode == ChaosEffect.ClockSkewMode.FREEZE ? Duration.ZERO : Duration.ofSeconds(10);
    final ChaosActivationHandle skew =
        plane.activate(
            ChaosScenario.builder("skew")
                .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)))
                .effect(ChaosEffect.skewClock(amount, mode))
                .activationPolicy(ActivationPolicy.always())
                .build());
    for (int read = 1; read <= 3; read++) {
      final long before = System.currentTimeMillis();
      final long skewed = Instant.now().toEpochMilli();
      final long after = System.currentTimeMillis();
      System.out.println("read" + read + "=" + skewed);
      printOffset("offset" + read, skewed, before, after);
      pause(200L);
    }
    skew.close();
    final long before = System.currentTimeMillis();
    final long unskewed = Instant.now().toEpochMilli();
    printOffset("offsetAfterStop", unskewed, before, System.currentTimeMillis());
  }

  /**
   * Prints the range the skew of {@code read} lies in: the real time was {@code before} to {@code
   * after} around it, so the skew is between {@code read - after} ({@code <name>Min}) and {@code
   * read - before} ({@code <name>Max}); a pause of the thread between the reads widens the range
   * instead of reading as skew.
   */
  private static void printOffset(
      final String name, final long read, final long before, final long after) {
    System.out.println(name + "Min=" + (read - after));
    System.out.println(name + "Max=" + (read - before));
  }

  // ── stressors ─────────────────────────────────────────────────────────────────────────────────

  private static long directUsed() {
    return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
        .filter(pool -> "direct".equals(pool.getName()))
        .findFirst()
        .orElseThrow()
        .getMemoryUsed();
  }

  /**
   * With 10 MiB of a 16 MiB direct-memory limit taken by the application, a 12 MiB direct-buffer
   * pressure runs out of direct memory while it starts: the activation fails, and the buffers it
   * had taken are freed.
   */
  private static void directBufferOom(final ChaosControlPlane plane) {
    final ByteBuffer application = ByteBuffer.allocateDirect(10 << 20);
    final long before = directUsed();
    String outcome;
    try {
      plane.activate(
          ChaosScenario.builder("direct-oom")
              .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
              .effect(ChaosEffect.directBufferPressure(12L << 20, 1 << 20))
              .activationPolicy(ActivationPolicy.always())
              .build());
      outcome = "activated";
    } catch (final ChaosUnsupportedFeatureException refused) {
      outcome = "refused";
      System.out.println("message=" + refused.getMessage());
    }
    System.out.println("outcome=" + outcome);
    System.out.println("directLeftMiB=" + (directUsed() - before) / (1024 * 1024));
    System.out.println("registered=" + plane.diagnostics().scenario("direct-oom").isPresent());
    System.out.println("application=" + application.capacity());
  }

  private static long heapUsedAfterGc() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      System.gc();
      pause(50L);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  /** 64 MiB of heap pressure: held while active, released on close. */
  private static void heapPressure(final ChaosControlPlane plane) throws Exception {
    final long before = heapUsedAfterGc();
    final ChaosActivationHandle heap =
        plane.activate(
            ChaosScenario.builder("heap")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.HEAP))
                .effect(ChaosEffect.heapPressure(64L << 20, 1 << 20))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final long during = heapUsedAfterGc();
    System.out.println("state=" + heap.state());
    heap.close();
    final long after = heapUsedAfterGc();
    System.out.println("heldMiB=" + (during - before) / (1024 * 1024));
    System.out.println("leftMiB=" + (after - before) / (1024 * 1024));
  }

  private static long poolUsed(final java.util.function.Predicate<String> name) {
    return ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(pool -> name.test(pool.getName()))
        .mapToLong(pool -> pool.getUsage().getUsed())
        .sum();
  }

  /** 2,000 generated classes held in the metaspace while active, unloadable once closed. */
  private static void metaspacePressure(final ChaosControlPlane plane) throws Exception {
    final java.lang.management.ClassLoadingMXBean classes =
        ManagementFactory.getClassLoadingMXBean();
    final long loadedBefore = classes.getTotalLoadedClassCount();
    final long metaspaceBefore = poolUsed("Metaspace"::equals);
    final long unloadedBefore = classes.getUnloadedClassCount();
    final ChaosActivationHandle metaspace =
        plane.activate(
            ChaosScenario.builder("metaspace")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.METASPACE))
                .effect(new ChaosEffect.MetaspacePressureEffect(2_000, 4, true))
                .activationPolicy(ActivationPolicy.always())
                .build());
    System.out.println("loadedClasses=" + (classes.getTotalLoadedClassCount() - loadedBefore));
    System.out.println(
        "metaspaceGrowthKiB=" + (poolUsed("Metaspace"::equals) - metaspaceBefore) / 1024);
    metaspace.close();
    heapUsedAfterGc();
    System.out.println("unloadedClasses=" + (classes.getUnloadedClassCount() - unloadedBefore));
  }

  /** Generated classes compiled by the JIT: the code cache grows and compilation time is spent. */
  private static void codeCachePressure(final ChaosControlPlane plane) throws Exception {
    final java.lang.management.CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
    final long codeBefore =
        poolUsed(name -> name.startsWith("CodeHeap") || name.equals("CodeCache"));
    final long jitBefore = jit.getTotalCompilationTime();
    final ChaosActivationHandle codeCache =
        plane.activate(
            ChaosScenario.builder("code-cache")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.CODE_CACHE_PRESSURE))
                .effect(new ChaosEffect.CodeCachePressureEffect(200, 20))
                .activationPolicy(ActivationPolicy.always())
                .build());
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    long codeGrowth = 0L;
    while (System.nanoTime() - deadline < 0) {
      codeGrowth =
          poolUsed(name -> name.startsWith("CodeHeap") || name.equals("CodeCache")) - codeBefore;
      if (codeGrowth > 512 * 1024 && jit.getTotalCompilationTime() > jitBefore) {
        break;
      }
      pause(100L);
    }
    System.out.println("codeCacheGrowthKiB=" + codeGrowth / 1024);
    System.out.println("compilationMillis=" + (jit.getTotalCompilationTime() - jitBefore));
    codeCache.close();
  }

  /**
   * 10,000 distinct strings interned through the agent; the JVM's string table is printed at exit.
   */
  private static void stringInternPressure(final ChaosControlPlane plane) {
    final ChaosActivationHandle intern =
        plane.activate(
            ChaosScenario.builder("intern")
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.STRING_INTERN_PRESSURE))
                .effect(ChaosEffect.stringInternPressure(10_000, 32))
                .activationPolicy(ActivationPolicy.always())
                .build());
    System.out.println("state=" + intern.state());
  }
}
