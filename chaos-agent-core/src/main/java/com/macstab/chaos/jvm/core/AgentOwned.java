package com.macstab.chaos.jvm.core;

/**
 * Marks an object the agent creates for its own work, so no scenario is evaluated for it (L3):
 * {@link ExecutorDispatch#beforeForkJoinTaskRun} skips a fork-join task that implements it, and
 * {@link ThreadDispatch#beforeThreadLocalGet} / {@link ThreadDispatch#beforeThreadLocalSet} skip a
 * {@link ThreadLocal} that implements it. Both checks run after the idle check, so an idle hook
 * pays nothing for them.
 */
interface AgentOwned {}
