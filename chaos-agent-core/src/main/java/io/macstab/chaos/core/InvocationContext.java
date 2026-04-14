package io.macstab.chaos.core;

import io.macstab.chaos.api.OperationType;

record InvocationContext(
    OperationType operationType,
    String targetClassName,
    String subjectClassName,
    String targetName,
    boolean periodic,
    Boolean daemonThread,
    Boolean virtualThread,
    String sessionId) {}
