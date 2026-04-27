package com.macstab.chaos.jvm.examples.sb4sla;

/**
 * Aggregated result of a fan-out call to three downstream services.
 *
 * @param a response from downstream service A
 * @param b response from downstream service B
 * @param c response from downstream service C
 */
public record FanOutResult(String a, String b, String c) {}
