package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;
import net.bytebuddy.matcher.ElementMatcher;
import org.jspecify.annotations.Nullable;

/**
 * Decides, once per class loader, whether the classes it defines may be woven: advice woven into
 * them calls {@code BootstrapDispatcher}, and only the copy on the bootstrap class path carries the
 * agent's delegate. A loader that sees a {@code BootstrapDispatcher} class file of its own (a
 * {@code chaos-agent-instrumentation-jdk} jar in a web application's {@code WEB-INF/lib}, a module
 * that bundles it) would link its classes against a copy without a delegate, and every hook in them
 * would be inert. Its classes are not woven, and it is reported in the hook failures.
 *
 * <p>The decision is made on the loader's first sight in the type matcher, which runs inside {@code
 * ClassFileTransformer.transform} for a loader created later. It therefore only reads a resource,
 * as ByteBuddy's own class file locator does inside transforms, and never loads a class: loading
 * one there risks a {@code ClassCircularityError}, a deadlock with a parallel-capable loader, and
 * defines the very copy it asks about. The lookup is an agent-internal call ({@code getResource} is
 * a hook itself) and runs outside the lock of the cache.
 *
 * <p>The bridge appended to the bootstrap class path at run time is invisible to {@code
 * getResource}, so a loader that sees no class file weaves: its {@code BootstrapDispatcher}
 * resolves through its parents to the bootstrap copy. So does a loader that sees the very class
 * file the agent's own loader sees: it is that loader, or reads the file through it. A loader that
 * reaches neither its parents nor the bootstrap class path for the bridge package, and sees no copy
 * either, cannot be told apart from one that delegates; such a loader must delegate the package
 * {@code com.macstab.chaos.jvm.instrumentation} to the boot path (JBoss Modules {@code
 * jboss.modules.system.pkgs}, OSGi {@code org.osgi.framework.bootdelegation}).
 */
final class BridgeVisibility {
  private static final Logger LOGGER =
      Logger.getLogger(JdkInstrumentationInstaller.class.getName());

  /** The class file a loader would define its own {@code BootstrapDispatcher} from. */
  static final String DISPATCHER_CLASS_FILE =
      "com/macstab/chaos/jvm/instrumentation/bridge/BootstrapDispatcher.class";

  /** Per loader: whether it sees a copy of the bridge of its own. Read and written under itself. */
  private static final Map<ClassLoader, Boolean> OWN_COPY = new WeakHashMap<>();

  /**
   * The class file the agent's own loader sees ({@code null} in the agent jar, which keeps the
   * bridge under {@link BridgeInjector#BRIDGE_LAYOUT}); resolved on first use.
   */
  private static volatile @Nullable String agentCopy;

  private static volatile boolean agentCopyResolved;

  /**
   * Matches the class loaders whose classes must not be woven. ByteBuddy passes {@code null} for
   * the bootstrap class loader, whose classes are always woven.
   */
  @SuppressWarnings("NullAway") // ByteBuddy's matcher type is unannotated; null is the boot loader
  static final ElementMatcher<ClassLoader> OWN_BRIDGE_COPY =
      loader -> loader != null && seesOwnCopy(loader);

  private BridgeVisibility() {}

  /**
   * Returns whether {@code loader} sees a {@code BootstrapDispatcher} class file other than the one
   * the agent's own loader sees; the first decision for a loader is recorded in {@link
   * HookCoverage#failures()} when it does.
   */
  static boolean seesOwnCopy(final ClassLoader loader) {
    synchronized (OWN_COPY) {
      final Boolean known = OWN_COPY.get(loader);
      if (known != null) {
        return known;
      }
    }
    final String reason = ownCopyOf(loader);
    final boolean ownCopy = reason != null;
    final Boolean earlier;
    synchronized (OWN_COPY) {
      earlier = OWN_COPY.putIfAbsent(loader, ownCopy);
    }
    if (earlier != null) {
      return earlier;
    }
    if (reason != null) {
      HookCoverage.failed("class loader " + loader, reason);
      LOGGER.warning("chaos hooks are not woven into the classes of " + loader + ": " + reason);
    }
    return ownCopy;
  }

  /** Forgets every decision: the hooks were removed, a reinstall decides and reports anew. */
  static void reset() {
    synchronized (OWN_COPY) {
      OWN_COPY.clear();
    }
  }

  /** Returns why {@code loader}'s classes are not woven, or {@code null} when they are. */
  private static @Nullable String ownCopyOf(final ClassLoader loader) {
    final String seen;
    try {
      seen = classFileSeenBy(loader);
    } catch (final RuntimeException failure) {
      return "the bootstrap bridge class file could not be looked up (" + failure + ")";
    }
    if (seen == null || seen.equals(agentCopy())) {
      return null;
    }
    return "it sees a copy of the bootstrap bridge of its own ("
        + seen
        + "), which the agent's delegate does not reach; remove the chaos agent jar from it";
  }

  private static @Nullable String agentCopy() {
    if (!agentCopyResolved) {
      agentCopy = classFileSeenBy(BridgeVisibility.class.getClassLoader());
      agentCopyResolved = true;
    }
    return agentCopy;
  }

  /**
   * Returns the external form of the {@code BootstrapDispatcher} class file {@code loader} sees.
   * The text, not the {@link URL}: {@code URL.equals} may resolve host names.
   */
  private static @Nullable String classFileSeenBy(final ClassLoader loader) {
    final URL url;
    BootstrapDispatcher.beginInternalCall();
    try {
      url = loader.getResource(DISPATCHER_CLASS_FILE);
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
    return url == null ? null : url.toExternalForm();
  }
}
