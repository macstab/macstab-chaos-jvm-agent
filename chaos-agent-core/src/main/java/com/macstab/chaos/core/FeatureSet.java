package com.macstab.chaos.core;

import java.lang.reflect.Method;

final class FeatureSet {
  private final Method isVirtualMethod;
  private final int runtimeFeatureVersion;
  private final boolean jfrSupported;

  FeatureSet() {
    this.runtimeFeatureVersion = Runtime.version().feature();
    Method method;
    try {
      method = Thread.class.getMethod("isVirtual");
    } catch (NoSuchMethodException ignored) {
      method = null;
    }
    this.isVirtualMethod = method;
    this.jfrSupported = probeJfr();
  }

  boolean supportsVirtualThreads() {
    return isVirtualMethod != null;
  }

  boolean jfrSupported() {
    return jfrSupported;
  }

  private static boolean probeJfr() {
    try {
      Class.forName("jdk.jfr.FlightRecorder", false, FeatureSet.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }

  boolean isVirtualThread(final Thread thread) {
    if (thread == null || isVirtualMethod == null) {
      return false;
    }
    try {
      return (boolean) isVirtualMethod.invoke(thread);
    } catch (ReflectiveOperationException ignored) {
      return false;
    }
  }

  int runtimeFeatureVersion() {
    return runtimeFeatureVersion;
  }
}
