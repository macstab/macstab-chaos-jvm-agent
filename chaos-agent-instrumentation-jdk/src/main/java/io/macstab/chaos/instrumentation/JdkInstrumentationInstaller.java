package io.macstab.chaos.instrumentation;

import io.macstab.chaos.core.ChaosRuntime;
import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import io.macstab.chaos.instrumentation.bridge.BridgeDelegate;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

public final class JdkInstrumentationInstaller {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  private JdkInstrumentationInstaller() {}

  public static void install(
      final Instrumentation instrumentation,
      final ChaosRuntime runtime,
      final boolean premainMode) {
    injectBridge(instrumentation);
    installDelegate(new ChaosBridge(runtime));

    AgentBuilder agentBuilder =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(
                new AgentBuilder.Listener.Adapter() {
                  @Override
                  public void onError(
                      final String typeName,
                      final ClassLoader classLoader,
                      final JavaModule module,
                      final boolean loaded,
                      final Throwable throwable) {
                    LOGGER.warning(
                        "chaos instrumentation failed for " + typeName + ": " + throwable);
                  }
                })
            .ignore(
                ElementMatchers.nameStartsWith("net.bytebuddy.")
                    .or(ElementMatchers.nameStartsWith("io.macstab.chaos.")));

    agentBuilder =
        agentBuilder
            .type(ElementMatchers.named("java.lang.Thread"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder.visit(
                        Advice.to(ThreadAdvice.StartAdvice.class)
                            .on(
                                ElementMatchers.named("start")
                                    .and(ElementMatchers.takesArguments(0)))))
            .type(ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder
                        .visit(
                            Advice.to(ExecutorAdvice.ExecuteAdvice.class)
                                .on(
                                    ElementMatchers.named("execute")
                                        .and(ElementMatchers.takesArguments(Runnable.class))))
                        .visit(
                            Advice.to(ExecutorAdvice.BeforeExecuteAdvice.class)
                                .on(
                                    ElementMatchers.named("beforeExecute")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Thread.class, Runnable.class))))
                        .visit(
                            Advice.to(ExecutorAdvice.ShutdownAdvice.class)
                                .on(
                                    ElementMatchers.named("shutdown")
                                        .and(ElementMatchers.takesArguments(0))))
                        .visit(
                            Advice.to(ExecutorAdvice.AwaitTerminationAdvice.class)
                                .on(
                                    ElementMatchers.named("awaitTermination")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                long.class, java.util.concurrent.TimeUnit.class)))))
            .type(ElementMatchers.named("java.util.concurrent.ScheduledThreadPoolExecutor"))
            .transform(
                (builder, typeDescription, classLoader, module, protectionDomain) ->
                    builder
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.ScheduleRunnableAdvice.class)
                                .on(
                                    ElementMatchers.named("schedule")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.ScheduleCallableAdvice.class)
                                .on(
                                    ElementMatchers.named("schedule")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Callable.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.PeriodicAdvice.class)
                                .on(
                                    ElementMatchers.named("scheduleAtFixedRate")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class))))
                        .visit(
                            Advice.to(ScheduledExecutorAdvice.PeriodicAdvice.class)
                                .on(
                                    ElementMatchers.named("scheduleWithFixedDelay")
                                        .and(
                                            ElementMatchers.takesArguments(
                                                Runnable.class,
                                                long.class,
                                                long.class,
                                                java.util.concurrent.TimeUnit.class)))));

    if (premainMode) {
      agentBuilder =
          agentBuilder
              .type(ElementMatchers.isSubTypeOf(java.util.concurrent.BlockingQueue.class))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(QueueAdvice.PutAdvice.class)
                                  .on(ElementMatchers.named("put")))
                          .visit(
                              Advice.to(QueueAdvice.TakeAdvice.class)
                                  .on(ElementMatchers.named("take")))
                          .visit(
                              Advice.to(QueueAdvice.PollAdvice.class)
                                  .on(ElementMatchers.named("poll")))
                          .visit(
                              Advice.to(QueueAdvice.OfferAdvice.class)
                                  .on(
                                      ElementMatchers.named("offer")
                                          .and(ElementMatchers.takesArguments(1)))))
              .type(ElementMatchers.named("java.util.concurrent.CompletableFuture"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(CompletableFutureAdvice.CompleteAdvice.class)
                                  .on(
                                      ElementMatchers.named("complete")
                                          .and(ElementMatchers.takesArguments(1))))
                          .visit(
                              Advice.to(CompletableFutureAdvice.CompleteExceptionallyAdvice.class)
                                  .on(
                                      ElementMatchers.named("completeExceptionally")
                                          .and(ElementMatchers.takesArguments(Throwable.class)))))
              .type(ElementMatchers.named("java.lang.ClassLoader"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(ClassLoaderAdvice.LoadClassAdvice.class)
                                  .on(
                                      ElementMatchers.named("loadClass")
                                          .and(
                                              ElementMatchers.takesArguments(
                                                      String.class, boolean.class)
                                                  .or(
                                                      ElementMatchers.takesArguments(
                                                          String.class)))))
                          .visit(
                              Advice.to(ClassLoaderAdvice.GetResourceAdvice.class)
                                  .on(
                                      ElementMatchers.named("getResource")
                                          .and(ElementMatchers.takesArguments(String.class)))))
              .type(ElementMatchers.named("java.lang.Runtime"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder
                          .visit(
                              Advice.to(ShutdownAdvice.AddShutdownHookAdvice.class)
                                  .on(
                                      ElementMatchers.named("addShutdownHook")
                                          .and(ElementMatchers.takesArguments(Thread.class))))
                          .visit(
                              Advice.to(ShutdownAdvice.RemoveShutdownHookAdvice.class)
                                  .on(
                                      ElementMatchers.named("removeShutdownHook")
                                          .and(ElementMatchers.takesArguments(Thread.class)))))
              .type(ElementMatchers.named("java.util.concurrent.ForkJoinTask"))
              .transform(
                  (builder, typeDescription, classLoader, module, protectionDomain) ->
                      builder.visit(
                          Advice.to(ForkJoinAdvice.DoExecAdvice.class)
                              .on(
                                  ElementMatchers.named("doExec")
                                      .and(ElementMatchers.takesArguments(0)))));
    }

    agentBuilder.installOn(instrumentation);
  }

  static MethodHandle[] buildMethodHandles() throws Exception {
    final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    final Class<?> cls = BridgeDelegate.class;
    final MethodHandle[] mh = new MethodHandle[BootstrapDispatcher.HANDLE_COUNT];
    mh[BootstrapDispatcher.DECORATE_EXECUTOR_RUNNABLE] =
        lookup.findVirtual(
            cls,
            "decorateExecutorRunnable",
            MethodType.methodType(Runnable.class, String.class, Object.class, Runnable.class));
    mh[BootstrapDispatcher.DECORATE_EXECUTOR_CALLABLE] =
        lookup.findVirtual(
            cls,
            "decorateExecutorCallable",
            MethodType.methodType(Callable.class, String.class, Object.class, Callable.class));
    mh[BootstrapDispatcher.BEFORE_THREAD_START] =
        lookup.findVirtual(
            cls, "beforeThreadStart", MethodType.methodType(void.class, Thread.class));
    mh[BootstrapDispatcher.BEFORE_WORKER_RUN] =
        lookup.findVirtual(
            cls,
            "beforeWorkerRun",
            MethodType.methodType(void.class, Object.class, Thread.class, Runnable.class));
    mh[BootstrapDispatcher.BEFORE_FORK_JOIN_TASK_RUN] =
        lookup.findVirtual(
            cls, "beforeForkJoinTaskRun", MethodType.methodType(void.class, ForkJoinTask.class));
    mh[BootstrapDispatcher.ADJUST_SCHEDULE_DELAY] =
        lookup.findVirtual(
            cls,
            "adjustScheduleDelay",
            MethodType.methodType(
                long.class, String.class, Object.class, Object.class, long.class, boolean.class));
    mh[BootstrapDispatcher.BEFORE_SCHEDULED_TICK] =
        lookup.findVirtual(
            cls,
            "beforeScheduledTick",
            MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class));
    mh[BootstrapDispatcher.BEFORE_QUEUE_OPERATION] =
        lookup.findVirtual(
            cls,
            "beforeQueueOperation",
            MethodType.methodType(void.class, String.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_BOOLEAN_QUEUE_OPERATION] =
        lookup.findVirtual(
            cls,
            "beforeBooleanQueueOperation",
            MethodType.methodType(Boolean.class, String.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_COMPLETABLE_FUTURE_COMPLETE] =
        lookup.findVirtual(
            cls,
            "beforeCompletableFutureComplete",
            MethodType.methodType(
                Boolean.class, String.class, CompletableFuture.class, Object.class));
    mh[BootstrapDispatcher.BEFORE_CLASS_LOAD] =
        lookup.findVirtual(
            cls,
            "beforeClassLoad",
            MethodType.methodType(void.class, ClassLoader.class, String.class));
    mh[BootstrapDispatcher.AFTER_RESOURCE_LOOKUP] =
        lookup.findVirtual(
            cls,
            "afterResourceLookup",
            MethodType.methodType(URL.class, ClassLoader.class, String.class, URL.class));
    mh[BootstrapDispatcher.DECORATE_SHUTDOWN_HOOK] =
        lookup.findVirtual(
            cls, "decorateShutdownHook", MethodType.methodType(Thread.class, Thread.class));
    mh[BootstrapDispatcher.RESOLVE_SHUTDOWN_HOOK] =
        lookup.findVirtual(
            cls, "resolveShutdownHook", MethodType.methodType(Thread.class, Thread.class));
    mh[BootstrapDispatcher.BEFORE_EXECUTOR_SHUTDOWN] =
        lookup.findVirtual(
            cls,
            "beforeExecutorShutdown",
            MethodType.methodType(void.class, String.class, Object.class, long.class));
    return mh;
  }

  private static void installDelegate(final Object bridgeDelegate) {
    try {
      final MethodHandle[] mh = buildMethodHandles();
      // Use reflection to call install on the bootstrap CL version of BootstrapDispatcher
      final Class<?> bootstrapDispatcher =
          Class.forName("io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher", true, null);
      bootstrapDispatcher
          .getMethod("install", Object.class, MethodHandle[].class)
          .invoke(null, bridgeDelegate, mh);
    } catch (Exception exception) {
      throw new IllegalStateException("failed to install bridge delegate", exception);
    }
  }

  private static void injectBridge(final Instrumentation instrumentation) {
    try {
      final Path bridgeJar = Files.createTempFile("macstab-chaos-bootstrap-bridge", ".jar");
      bridgeJar.toFile().deleteOnExit();
      try (JarOutputStream jarOutputStream =
          new JarOutputStream(Files.newOutputStream(bridgeJar))) {
        writeClass(
            jarOutputStream, "io/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.class");
        writeClass(
            jarOutputStream,
            "io/macstab/chaos/instrumentation/bridge/BootstrapDispatcher$ThrowingSupplier.class");
      }
      instrumentation.appendToBootstrapClassLoaderSearch(
          new java.util.jar.JarFile(bridgeJar.toFile()));
    } catch (IOException exception) {
      throw new IllegalStateException("failed to inject bootstrap bridge", exception);
    }
  }

  private static void writeClass(final JarOutputStream jarOutputStream, final String resourcePath)
      throws IOException {
    final JarEntry jarEntry = new JarEntry(resourcePath);
    jarOutputStream.putNextEntry(jarEntry);
    try (InputStream inputStream =
        JdkInstrumentationInstaller.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IOException("missing bridge resource " + resourcePath);
      }
      inputStream.transferTo(jarOutputStream);
    }
    jarOutputStream.closeEntry();
  }
}
