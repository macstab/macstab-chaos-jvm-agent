package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;

/**
 * A DNS answer to shape, with the seed of its shuffle so a fixed {@code randomSeed} reproduces the
 * order.
 *
 * @param effect the shaping effect
 * @param seed the shuffle's seed
 */
record DnsShape(ChaosEffect.DnsAnswerEffect effect, long seed) {}
