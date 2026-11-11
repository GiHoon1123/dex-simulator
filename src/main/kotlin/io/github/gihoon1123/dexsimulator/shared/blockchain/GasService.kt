package io.github.gihoon1123.dexsimulator.shared.blockchain

import org.springframework.stereotype.Service
import kotlin.random.Random

enum class GasPriority(
    val multiplier: Long,
) {
    LOW(1),
    MEDIUM(2),
    HIGH(5),

    /** Used by the MEV bot. */
    URGENT(10),
}

/** Gas prices (gwei) and gas estimates, ported from the original `GasService`. */
@Service
class GasService(
    private val random: Random,
) {
    fun calculateGasPrice(priority: GasPriority): Long = minOf(BASE_GAS_PRICE * priority.multiplier, MAX_GAS_PRICE)

    /** Base estimate of the type with a random +-10% variation. */
    fun estimateGas(type: TransactionType): Long {
        val base = GAS_ESTIMATES[type] ?: DEFAULT_GAS_ESTIMATE
        val factor = 1 + (random.nextDouble() - 0.5) * VARIATION
        return (base * factor).toLong()
    }

    fun calculateTotalCost(
        gasUsed: Long,
        gasPrice: Long,
    ): Long = gasUsed * gasPrice

    fun getCurrentGasPrice(): Long = BASE_GAS_PRICE

    /** [urgency] 0..1 maps to 1x..10x of the base price. */
    fun suggestGasPrice(urgency: Double): Long = minOf((BASE_GAS_PRICE * (1 + urgency * 9)).toLong(), MAX_GAS_PRICE)

    fun validateGasPrice(gasPrice: Long): Boolean = gasPrice in MIN_GAS_PRICE..MAX_GAS_PRICE

    companion object {
        const val BASE_GAS_PRICE = 100L
        const val MIN_GAS_PRICE = 50L
        const val MAX_GAS_PRICE = 10_000L
        const val DEFAULT_GAS_ESTIMATE = 200_000L
        private const val VARIATION = 0.2

        val GAS_ESTIMATES =
            mapOf(
                TransactionType.SWAP to 300_000L,
                TransactionType.ADD_LIQUIDITY to 400_000L,
                TransactionType.REMOVE_LIQUIDITY to 350_000L,
                TransactionType.TRANSFER to 21_000L,
                TransactionType.MEV_FRONTRUN to 300_000L,
                TransactionType.MEV_BACKRUN to 300_000L,
                TransactionType.MEV_SANDWICH to 500_000L,
            )
    }
}
