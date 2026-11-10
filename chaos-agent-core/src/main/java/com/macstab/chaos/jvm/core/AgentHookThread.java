package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;

/**
 * A thread of the agent that the JDK starts, not the agent: a shutdown hook. The JDK starts every
 * shutdown hook from its own loop with the dispatch depth at zero, so the agent cannot raise the
 * depth around the start; {@link ThreadDispatch#beforeThreadStart} skips a thread of this class
 * instead (L3), after the idle check. A {@code THREAD_START} reject would otherwise fail the
 * agent's hook and abort the JDK's loop, and the application's hooks after it would never start.
 * The body raises the depth itself. Not part of the stable API.
 */
@Internal
public final class AgentHookThread extends Thread implements AgentOwned {

  /**
   * Creates the unstarted hook thread.
   *
   * @param body the hook's work
   * @param name the thread name
   */
  public AgentHookThread(final Runnable body, final String name) {
    super(body, name);
  }
}
