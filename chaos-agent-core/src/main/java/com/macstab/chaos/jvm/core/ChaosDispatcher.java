package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * The hot-path dispatch of the chaos runtime: the entry points the instrumentation layer calls
 * through the bootstrap bridge (see {@code BootstrapDispatcher}), one class per domain — {@link
 * ThreadDispatch}, {@link ExecutorDispatch}, {@link MethodDispatch}, {@link ClockDispatch}, {@link
 * RuntimeDispatch}, {@link NetworkDispatch}, {@link DnsDispatch}, {@link FileDispatch}, {@link
 * ProcessDispatch}, {@link ClientDispatch} — over one shared {@link DispatchEngine}. Constructed by
 * {@link ChaosControlPlaneImpl}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe.
 *
 * <h2>API stability</h2>
 *
 * <p><strong>Not part of the stable API.</strong> This class is {@code public} because it is called
 * from the instrumentation classloader bridge and from benchmarks, not because its method surface
 * is frozen. The stable public surface of the chaos-agent is {@code
 * com.macstab.chaos.jvm.api.ChaosControlPlane}; bind to that interface instead. The {@link
 * Internal @Internal} marker signals to API linters and bytecode-compat tools that this class can
 * change without notice in any release.
 */
@Internal
public final class ChaosDispatcher {

  /**
   * What {@link NetworkDispatch#beforeDatagram} returns for a send a scenario suppresses: drop the
   * datagram.
   */
  public static final int DATAGRAM_DROPPED = DispatchEngine.DATAGRAM_DROPPED;

  /**
   * Tag of {@code scheduleAtFixedRate} / {@code scheduleWithFixedDelay}: a {@code SCHEDULE_SUBMIT}
   * of a periodic task ({@code SchedulingSelector.periodicOnly}).
   */
  public static final String SCHEDULE_SUBMIT_PERIODIC = DispatchEngine.SCHEDULE_SUBMIT_PERIODIC;

  /** Tag of {@code FileChannel.transferTo}: a read whose bytes never reach a buffer. */
  public static final String TRANSFER_TO = DispatchEngine.TRANSFER_TO;

  /** Tag of {@code FileChannel.transferFrom}: a write, torn as a short count. */
  public static final String TRANSFER_FROM = DispatchEngine.TRANSFER_FROM;

  private final DispatchEngine engine;
  private final ThreadDispatch thread;
  private final ExecutorDispatch executor;
  private final MethodDispatch method;
  private final ClockDispatch clock;
  private final RuntimeDispatch runtime;
  private final NetworkDispatch network;
  private final DnsDispatch dns;
  private final FileDispatch file;
  private final ProcessDispatch process;
  private final ClientDispatch client;

  ChaosDispatcher(final ChaosControlPlaneImpl controlPlane) {
    this.engine = new DispatchEngine(controlPlane);
    this.thread = new ThreadDispatch(engine);
    this.executor = new ExecutorDispatch(engine);
    this.method = new MethodDispatch(engine);
    this.clock = new ClockDispatch(engine);
    this.runtime = new RuntimeDispatch(engine);
    this.network = new NetworkDispatch(engine);
    this.dns = new DnsDispatch(engine);
    this.file = new FileDispatch(engine);
    this.process = new ProcessDispatch(engine);
    this.client = new ClientDispatch(engine);
  }

  /**
   * Returns the session id bound to the current thread, or {@code null} if none is active.
   *
   * @return the current thread's session id, or {@code null} when no session is bound
   */
  public @Nullable String currentSessionId() {
    return engine.currentSessionId();
  }

  /**
   * Returns the seed of the bit flip the current thread's read was just told to apply.
   *
   * @return the seed
   */
  public long corruptionSeed() {
    return engine.corruptionSeed();
  }

  /**
   * Returns the thread dispatch.
   *
   * @return the thread dispatch
   */
  public ThreadDispatch thread() {
    return thread;
  }

  /**
   * Returns the executor dispatch.
   *
   * @return the executor dispatch
   */
  public ExecutorDispatch executor() {
    return executor;
  }

  /**
   * Returns the method dispatch.
   *
   * @return the method dispatch
   */
  public MethodDispatch method() {
    return method;
  }

  /**
   * Returns the clock dispatch.
   *
   * @return the clock dispatch
   */
  public ClockDispatch clock() {
    return clock;
  }

  /**
   * Returns the runtime dispatch.
   *
   * @return the runtime dispatch
   */
  public RuntimeDispatch runtime() {
    return runtime;
  }

  /**
   * Returns the network dispatch.
   *
   * @return the network dispatch
   */
  public NetworkDispatch network() {
    return network;
  }

  /**
   * Returns the dns dispatch.
   *
   * @return the dns dispatch
   */
  public DnsDispatch dns() {
    return dns;
  }

  /**
   * Returns the file dispatch.
   *
   * @return the file dispatch
   */
  public FileDispatch file() {
    return file;
  }

  /**
   * Returns the process dispatch.
   *
   * @return the process dispatch
   */
  public ProcessDispatch process() {
    return process;
  }

  /**
   * Returns whether no scenario is indexed for the operation an operation tag names, so advice can
   * return before reading its target. {@link #SCHEDULE_SUBMIT_PERIODIC} is a {@code
   * SCHEDULE_SUBMIT}, {@link #TRANSFER_TO} a {@code FILE_IO_READ} and {@link #TRANSFER_FROM} a
   * {@code FILE_IO_WRITE}; every other tag is an {@link OperationType} name.
   *
   * @param operationTag the tag
   * @return {@code true} when no scenario is indexed for the operation
   */
  public boolean idle(final String operationTag) {
    final OperationType operation =
        switch (operationTag) {
          case SCHEDULE_SUBMIT_PERIODIC -> OperationType.SCHEDULE_SUBMIT;
          case TRANSFER_TO -> OperationType.FILE_IO_READ;
          case TRANSFER_FROM -> OperationType.FILE_IO_WRITE;
          default -> OperationType.valueOf(operationTag);
        };
    return engine.idle(operation);
  }

  /**
   * Returns the client dispatch.
   *
   * @return the client dispatch
   */
  public ClientDispatch client() {
    return client;
  }
}
