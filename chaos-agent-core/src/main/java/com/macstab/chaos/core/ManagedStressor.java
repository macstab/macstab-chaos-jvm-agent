package com.macstab.chaos.core;

interface ManagedStressor extends AutoCloseable {
  @Override
  void close();
}
