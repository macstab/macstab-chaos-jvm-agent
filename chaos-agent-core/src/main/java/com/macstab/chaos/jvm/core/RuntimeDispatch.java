package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.URL;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of JVM runtime operations: gc and exit requests, reflection, direct buffers,
 * serialization, class loading and definition, resources, native libraries, zip, JNDI and JMX.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class RuntimeDispatch {

  private final DispatchEngine engine;

  RuntimeDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before {@link ClassLoader#loadClass(String)} to apply scenarios targeting class loading.
   *
   * @param loader the loader performing the lookup; {@code null} for the bootstrap loader
   * @param className the binary name of the class being loaded
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeClassLoad(final @Nullable ClassLoader loader, final String className)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.CLASS_LOAD)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.CLASS_LOAD,
                    loader == null ? "bootstrap" : loader.getClass().getName(),
                    null,
                    className,
                    false,
                    null,
                    null,
                    null))));
  }

  /**
   * Called after {@link ClassLoader#getResource(String)} to optionally substitute the returned URL.
   *
   * @param loader the loader that performed the lookup; {@code null} for the bootstrap loader
   * @param name the resource name that was requested
   * @param currentValue the URL resolved by the real lookup; may be {@code null}
   * @return the (possibly substituted) URL to return to the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public @Nullable URL afterResourceLookup(
      final @Nullable ClassLoader loader, final String name, final @Nullable URL currentValue)
      throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.RESOURCE_LOAD)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.RESOURCE_LOAD,
                    loader == null ? "bootstrap" : loader.getClass().getName(),
                    null,
                    name,
                    false,
                    null,
                    null,
                    null)));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      if (decision.terminalAction() instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      // A rejected or suppressed lookup finds nothing.
      return null;
    }
    return currentValue;
  }

  /**
   * Called before {@code System.gc()}; returns {@code true} to suppress the GC request.
   *
   * @return {@code true} to suppress the real {@code System.gc()} call, {@code false} to let it
   *     proceed
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public boolean beforeGcRequest() throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.SYSTEM_GC_REQUEST)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SYSTEM_GC_REQUEST,
                    "java.lang.System",
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    return engine.suppressed(decision);
  }

  /**
   * Called before {@code System.exit(status)} to apply scenarios that may suppress or delay the
   * exit.
   *
   * @param status the exit status passed to {@code System.exit(int)}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeExitRequest(final int status) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.SYSTEM_EXIT_REQUEST)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SYSTEM_EXIT_REQUEST,
                    "java.lang.System",
                    null,
                    Integer.toString(status),
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@link java.lang.reflect.Method#invoke(Object, Object...)} to apply matching
   * scenarios.
   *
   * @param method the {@link java.lang.reflect.Method} or {@link java.lang.reflect.Constructor}
   *     being invoked; may be {@code null}
   * @param target the receiver of the reflective invocation (static methods pass {@code null})
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeReflectionInvoke(final @Nullable Object method, final @Nullable Object target)
      throws Throwable {
    if (engine.idle(OperationType.REFLECTION_INVOKE) || JdkOwnUse.isSerializationHook(method)) {
      return; // idle, or the object streams calling a class's serialization hook (L3a)
    }
    final String methodName;
    final String declaringClass;
    if (method instanceof java.lang.reflect.Method reflectMethod) {
      methodName = reflectMethod.getName();
      declaringClass = reflectMethod.getDeclaringClass().getName();
    } else if (method instanceof java.lang.reflect.Constructor<?> ctor) {
      methodName = "<init>";
      declaringClass = ctor.getDeclaringClass().getName();
    } else {
      methodName = null;
      declaringClass = method == null ? "java.lang.reflect.Method" : method.getClass().getName();
    }
    engine.applyPreDecision(
        engine.evaluate(
            new InvocationContext(
                OperationType.REFLECTION_INVOKE,
                declaringClass,
                target == null ? null : target.getClass().getName(),
                methodName,
                false,
                null,
                null,
                engine.scopeContext.currentSessionId())));
  }

  /**
   * Called before {@link java.nio.ByteBuffer#allocateDirect(int)} to apply direct-buffer scenarios.
   *
   * @param capacity the capacity (in bytes) requested by the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.DIRECT_BUFFER_ALLOCATE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.DIRECT_BUFFER_ALLOCATE,
                    "java.nio.ByteBuffer",
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@link java.io.ObjectInputStream#readObject()} to apply deserialization
   * scenarios.
   *
   * @param stream the {@link java.io.ObjectInputStream} performing the deserialization; may be
   *     {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeObjectDeserialize(final @Nullable Object stream) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.OBJECT_DESERIALIZE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.OBJECT_DESERIALIZE,
                    stream == null ? "java.io.ObjectInputStream" : stream.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code ClassLoader.defineClass(...)} to apply class-definition scenarios.
   *
   * @param loader the loader performing the definition; {@code null} for the bootstrap loader
   * @param className the binary name of the class being defined
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeClassDefine(final @Nullable Object loader, final @Nullable String className)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.CLASS_DEFINE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.CLASS_DEFINE,
                    loader == null ? "bootstrap" : loader.getClass().getName(),
                    null,
                    className,
                    false,
                    null,
                    null,
                    null))));
  }

  /**
   * Called before {@link javax.naming.InitialContext#lookup(String)} to apply JNDI scenarios.
   *
   * @param context the JNDI context performing the lookup
   * @param name the JNDI name being looked up
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    if (engine.idle(OperationType.JNDI_LOOKUP)) {
      return;
    }
    final InvocationContext invocationContext =
        new InvocationContext(
            OperationType.JNDI_LOOKUP,
            context == null ? "javax.naming.InitialContext" : context.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            engine.scopeContext.currentSessionId());
    engine.applyPreDecision(engine.evaluate(invocationContext));
  }

  /**
   * Called before {@link java.io.ObjectOutputStream#writeObject(Object)} to apply serialization
   * scenarios.
   *
   * @param stream the object output stream performing the write
   * @param obj the object being serialized
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeObjectSerialize(final Object stream, final @Nullable Object obj)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.OBJECT_SERIALIZE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.OBJECT_SERIALIZE,
                    stream == null ? "java.io.ObjectOutputStream" : stream.getClass().getName(),
                    obj == null ? null : obj.getClass().getName(),
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code System.loadLibrary} / {@code System.load} to apply native-library
   * scenarios.
   *
   * @param libraryName the name of the native library being loaded
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.NATIVE_LIBRARY_LOAD)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.NATIVE_LIBRARY_LOAD,
                    "java.lang.System",
                    null,
                    libraryName,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code Inflater.inflate(...)} to apply decompression scenarios.
   *
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeZipInflate() throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.ZIP_INFLATE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.ZIP_INFLATE,
                    "java.util.zip.Inflater",
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code Deflater.deflate(...)} to apply compression scenarios.
   *
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeZipDeflate() throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.ZIP_DEFLATE)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.ZIP_DEFLATE,
                    "java.util.zip.Deflater",
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code MBeanServer.invoke(...)} to apply JMX invoke scenarios. The agent's own
   * MBeans ({@code com.macstab.chaos.jvm}) are never a target (L3).
   *
   * @param server the {@code MBeanServer} receiving the invocation
   * @param objectName the target MBean {@code ObjectName}
   * @param operationName the name of the JMX operation being invoked
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.JMX_INVOKE) || JdkOwnUse.isAgentMBean(objectName)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.JMX_INVOKE,
                    server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
                    String.valueOf(objectName),
                    operationName,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@code MBeanServer.getAttribute(...)} and {@code getAttributes(...)} to apply JMX
   * attribute-read scenarios. The agent's own MBeans ({@code com.macstab.chaos.jvm}) are never a
   * target (L3).
   *
   * @param server the {@code MBeanServer} receiving the attribute read
   * @param objectName the target MBean {@code ObjectName}
   * @param attribute the attribute name being read; the names of a {@code getAttributes} read,
   *     comma-separated
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.JMX_GET_ATTR) || JdkOwnUse.isAgentMBean(objectName)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.JMX_GET_ATTR,
                    server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
                    String.valueOf(objectName),
                    attribute,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }
}
