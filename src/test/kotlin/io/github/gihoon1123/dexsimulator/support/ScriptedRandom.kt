package io.github.gihoon1123.dexsimulator.support

import kotlin.random.Random

/**
 * Random that returns scripted doubles in order, then repeats [fallback].
 * Replaces the original tests' `jest.spyOn(Math, 'random')`.
 */
class ScriptedRandom(
    vararg values: Double,
    private val fallback: Double = 0.5,
) : Random() {
    private val queue = ArrayDeque(values.toList())

    fun push(vararg values: Double) = queue.addAll(values.toList())

    override fun nextDouble(): Double = queue.removeFirstOrNull() ?: fallback

    override fun nextInt(until: Int): Int = (nextDouble() * until).toInt().coerceIn(0, until - 1)

    override fun nextBoolean(): Boolean = nextDouble() > 0.5

    override fun nextBits(bitCount: Int): Int = (nextDouble() * (1L shl bitCount)).toInt()
}
