package com.macstab.chaos.core;

import com.macstab.chaos.api.OperationType;

record InvocationContext(
    OperationType operationType,
    String targetClassName,
    String subjectClassName,
    String targetName,
    boolean periodic,
    Boolean daemonThread,
    Boolean virtualThread,
    String sessionId) {}
