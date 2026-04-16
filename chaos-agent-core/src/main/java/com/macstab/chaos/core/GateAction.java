package com.macstab.chaos.core;

import java.time.Duration;

record GateAction(ManualGate gate, Duration maxBlock) {}
