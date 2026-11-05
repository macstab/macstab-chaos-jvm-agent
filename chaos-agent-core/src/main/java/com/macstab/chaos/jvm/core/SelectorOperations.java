package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;

/** The operations a selector matches. */
public final class SelectorOperations {

  private SelectorOperations() {}

  /**
   * Returns the operations {@code selector} matches; a stress selector matches no intercepted call.
   *
   * @param selector the selector; never {@code null}
   * @return the operations; never {@code null}
   */
  public static Set<OperationType> of(final ChaosSelector selector) {
    return switch (selector) {
      case ChaosSelector.ThreadSelector threadSel -> threadSel.operations();
      case ChaosSelector.ExecutorSelector executorSel -> executorSel.operations();
      case ChaosSelector.QueueSelector queueSel -> queueSel.operations();
      case ChaosSelector.AsyncSelector asyncSel -> asyncSel.operations();
      case ChaosSelector.SchedulingSelector schedulingSel -> schedulingSel.operations();
      case ChaosSelector.ShutdownSelector shutdownSel -> shutdownSel.operations();
      case ChaosSelector.ClassLoadingSelector classLoadingSel -> classLoadingSel.operations();
      case ChaosSelector.MethodSelector methodSel -> methodSel.operations();
      case ChaosSelector.MonitorSelector monitorSel -> monitorSel.operations();
      case ChaosSelector.JvmRuntimeSelector jvmRuntimeSel -> jvmRuntimeSel.operations();
      case ChaosSelector.NioSelector nioSel -> nioSel.operations();
      case ChaosSelector.NetworkSelector networkSel -> networkSel.operations();
      case ChaosSelector.ThreadLocalSelector threadLocalSel -> threadLocalSel.operations();
      case ChaosSelector.HttpClientSelector httpClientSel -> httpClientSel.operations();
      case ChaosSelector.JdbcSelector jdbcSel -> jdbcSel.operations();
      case ChaosSelector.DnsSelector dnsSel -> dnsSel.operations();
      case ChaosSelector.SslSelector sslSel -> sslSel.operations();
      case ChaosSelector.FileIoSelector fileIoSel -> fileIoSel.operations();
      case ChaosSelector.MessagingSelector messagingSel -> messagingSel.operations();
      case ChaosSelector.RpcSelector rpcSel -> rpcSel.operations();
      case ChaosSelector.RedisSelector redisSel -> redisSel.operations();
      case ChaosSelector.AwsSdkSelector awsSdkSel -> awsSdkSel.operations();
      case ChaosSelector.NettySelector nettySel -> nettySel.operations();
      case ChaosSelector.ProcessSelector processSel -> processSel.operations();
      case ChaosSelector.StressSelector ignored -> Set.of();
    };
  }
}
