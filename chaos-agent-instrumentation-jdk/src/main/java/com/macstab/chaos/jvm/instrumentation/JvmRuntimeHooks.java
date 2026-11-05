package com.macstab.chaos.jvm.instrumentation;

import static com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller.advise;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The hook groups for class loading and definition, GC and exit, reflection, direct buffers,
 * serialization and ZIP: which methods of which types receive which advice. {@link
 * JdkInstrumentationInstaller.HookGroup} weaves them on demand.
 */
final class JvmRuntimeHooks {
  private JvmRuntimeHooks() {}

  /** ClassLoader loadClass / getResource instrumentation. */
  static AgentBuilder applyClassLoaderTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ClassLoader"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder
                    .visit(
                        // Must match BOTH 1-arg and 2-arg overloads: BuiltinClassLoader
                        // (the superclass of every default JDK classloader) overrides the
                        // 2-arg variant but inherits the 1-arg from java.lang.ClassLoader,
                        // so instrumenting only the 2-arg would miss every standard class
                        // load via Class.forName / loader.loadClass(name). Loaders that
                        // inherit both overloads (URLClassLoader) and parent delegation reach
                        // the advice more than once per load; the SCOPE_CLASS_LOAD call-depth
                        // scope in LoadClassAdvice evaluates only the outermost call.
                        advise(
                            ClassLoaderAdvice.LoadClassAdvice.class,
                            ElementMatchers.named("loadClass")
                                .and(
                                    ElementMatchers.takesArguments(String.class, boolean.class)
                                        .or(ElementMatchers.takesArguments(String.class)))))
                    .visit(
                        advise(
                            ClassLoaderAdvice.GetResourceAdvice.class,
                            ElementMatchers.named("getResource")
                                .and(ElementMatchers.takesArguments(String.class)))));
  }

  /** {@code ClassLoader.defineClass} interception (CLASS_DEFINE). */
  static AgentBuilder applyClassDefineTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.ClassLoader"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ClassDefineAdvice.class,
                        ElementMatchers.named("defineClass")
                            .and(ElementMatchers.takesArgument(0, String.class)))));
  }

  /** System.exit, System.gc, Runtime.gc, Runtime.halt instrumentation. */
  static AgentBuilder applyRuntimeLifecycleTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.System"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                // System.gc() is a non-native Java method that delegates to Runtime.gc().
                // Runtime.gc() is native and cannot be instrumented; System.gc() can be.
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.GcRequestAdvice.class,
                        ElementMatchers.named("gc").and(ElementMatchers.takesArguments(0)))))
        // System.exit(int) delegates to Runtime.exit(int); hooking Runtime.exit covers both
        // entry points once. Runtime.halt does not go through Runtime.exit.
        .type(ElementMatchers.named("java.lang.Runtime"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ExitRequestAdvice.class,
                        ElementMatchers.namedOneOf("exit", "halt")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /** {@code Method.invoke} interception (REFLECTION_INVOKE). */
  static AgentBuilder applyReflectionInvokeTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.lang.reflect.Method"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ReflectionInvokeAdvice.class,
                        ElementMatchers.named("invoke")
                            .and(ElementMatchers.takesArguments(Object.class, Object[].class)))));
  }

  /** {@code ByteBuffer.allocateDirect} interception (DIRECT_BUFFER_ALLOCATE). */
  static AgentBuilder applyDirectBufferTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.nio.ByteBuffer"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.DirectBufferAllocateAdvice.class,
                        ElementMatchers.named("allocateDirect")
                            .and(ElementMatchers.takesArguments(int.class)))));
  }

  /** {@code ObjectInputStream.readObject} / {@code ObjectOutputStream.writeObject} interception. */
  static AgentBuilder applySerializationTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.io.ObjectInputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ObjectDeserializeAdvice.class,
                        ElementMatchers.named("readObject")
                            .and(ElementMatchers.takesArguments(0)))))
        .type(ElementMatchers.named("java.io.ObjectOutputStream"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ObjectSerializeAdvice.class,
                        ElementMatchers.named("writeObject")
                            .and(ElementMatchers.takesArguments(Object.class)))));
  }

  /** ZIP Inflater / Deflater instrumentation. */
  static AgentBuilder applyZipTransformations(final AgentBuilder builder) {
    return builder
        .type(ElementMatchers.named("java.util.zip.Inflater"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ZipInflateAdvice.class, ElementMatchers.named("inflate"))))
        .type(ElementMatchers.named("java.util.zip.Deflater"))
        .transform(
            (typeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                typeBuilder.visit(
                    advise(
                        JvmRuntimeAdvice.ZipDeflateAdvice.class,
                        ElementMatchers.named("deflate"))));
  }
}
