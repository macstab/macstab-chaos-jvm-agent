package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosActivationHandle;
import io.macstab.chaos.api.ChaosPlan;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSession;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

final class DefaultChaosSession implements ChaosSession {
  private final String id = UUID.randomUUID().toString();
  private final String displayName;
  private final ScopeContext scopeContext;
  private final ChaosRuntime runtime;
  private final List<DefaultChaosActivationHandle> handles = new CopyOnWriteArrayList<>();
  private final ScopeBinding rootBinding;

  DefaultChaosSession(
      final String displayName, final ScopeContext scopeContext, final ChaosRuntime runtime) {
    this.displayName = displayName;
    this.scopeContext = scopeContext;
    this.runtime = runtime;
    this.rootBinding = scopeContext.bind(id);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String displayName() {
    return displayName;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    final DefaultChaosActivationHandle handle = runtime.activateInSession(this, scenario);
    handles.add(handle);
    return handle;
  }

  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    final List<ChaosActivationHandle> children =
        plan.scenarios().stream().map(this::activate).toList();
    return new CompositeActivationHandle("session-plan:" + plan.metadata().name(), children);
  }

  @Override
  public ScopeBinding bind() {
    return scopeContext.bind(id);
  }

  @Override
  public Runnable wrap(final Runnable runnable) {
    return scopeContext.wrap(id, runnable);
  }

  @Override
  public <T> Callable<T> wrap(final Callable<T> callable) {
    return scopeContext.wrap(id, callable);
  }

  @Override
  public void close() {
    handles.forEach(DefaultChaosActivationHandle::destroy);
    rootBinding.close();
  }
}
