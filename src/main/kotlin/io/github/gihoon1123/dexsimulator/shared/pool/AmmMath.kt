package io.github.gihoon1123.dexsimulator.shared.pool

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Token decimals used to fix the scale of every amount.
 *
 * Amounts are [BigDecimal]s at exactly the token's decimals, which is the same set of
 * values as integer minimal units (wei for ETH, 10^-6 for USDC) but readable in JSON.
 */
object Tokens {
    const val DEFAULT_DECIMALS = 18

    private val DECIMALS =
        mapOf(
            "ETH" to 18,
            "WETH" to 18,
            "DAI" to 18,
            "USDC" to 6,
            "USDT" to 6,
            "BTC" to 8,
        )

    fun decimals(symbol: String): Int = DECIMALS[symbol] ?: DEFAULT_DECIMALS

    /** Truncates [amount] to the token's minimal unit, like integer math on chain. */
    fun scale(
        amount: BigDecimal,
        symbol: String,
    ): BigDecimal = amount.setScale(decimals(symbol), RoundingMode.DOWN)
}

data class SwapQuote(
    val amountInAfterFee: BigDecimal,
    val fee: BigDecimal,
    val amountOut: BigDecimal,
    /** Price impact in percent. Display value. */
    val priceImpact: Double,
)

/**
 * Constant product (x * y = k) math. Pure functions so that they are tested without Spring.
 *
 * Rounding always favors the pool: the amount that enters after the fee and every amount
 * that leaves the pool are truncated to the token's minimal unit. The original computed
 * everything in floating point.
 */
object AmmMath {
    /** Precision for intermediate ratios (34 significant digits). */
    val CALC: MathContext = MathContext.DECIMAL128

    /**
     * Quotes a swap of [amountIn] (already at [decimalsIn] scale).
     *
     * `out = inAfterFee * reserveOut / (reserveIn + inAfterFee)` with the whole input,
     * including the fee, staying in the pool.
     */
    fun quoteSwap(
        amountIn: BigDecimal,
        reserveIn: BigDecimal,
        reserveOut: BigDecimal,
        feeRate: BigDecimal,
        decimalsIn: Int,
        decimalsOut: Int,
    ): SwapQuote {
        val amountInAfterFee = (amountIn * (BigDecimal.ONE - feeRate)).setScale(decimalsIn, RoundingMode.DOWN)
        val fee = amountIn - amountInAfterFee
        val newReserveIn = reserveIn + amountInAfterFee
        val amountOut = (amountInAfterFee * reserveOut).divide(newReserveIn, decimalsOut, RoundingMode.DOWN)

        val currentPrice = reserveOut.divide(reserveIn, CALC)
        val newPrice = (reserveOut - amountOut).divide(newReserveIn, CALC)
        val impact = (newPrice - currentPrice).abs().divide(currentPrice, CALC).toDouble() * 100

        return SwapQuote(amountInAfterFee, fee, amountOut, impact)
    }

    /** `(amountOutMin - amountOut) / amountOut * 100`, or 0 when nothing comes out. */
    fun slippagePercent(
        amountOutMin: BigDecimal,
        amountOut: BigDecimal,
    ): Double =
        if (amountOut.signum() == 0) {
            0.0
        } else {
            (amountOutMin - amountOut).divide(amountOut, CALC).toDouble() * 100
        }

    /**
     * Adjusts a deposit to the pool ratio, keeping the side that is short and reducing the
     * other. Compared by cross multiplication, so a zero amount does not divide by zero.
     */
    fun matchPoolRatio(
        amountA: BigDecimal,
        amountB: BigDecimal,
        reserveA: BigDecimal,
        reserveB: BigDecimal,
        decimalsA: Int,
        decimalsB: Int,
    ): Pair<BigDecimal, BigDecimal> {
        val input = amountA * reserveB
        val pool = amountB * reserveA
        return when {
            input > pool -> (amountB * reserveA).divide(reserveB, decimalsA, RoundingMode.DOWN) to amountB
            input < pool -> amountA to (amountA * reserveB).divide(reserveA, decimalsB, RoundingMode.DOWN)
            else -> amountA to amountB
        }
    }

    /** Liquidity units of a position, `sqrt(a * b)` truncated to 18 decimals. */
    fun liquidity(
        amountA: BigDecimal,
        amountB: BigDecimal,
    ): BigDecimal = (amountA * amountB).sqrt(CALC).setScale(LIQUIDITY_DECIMALS, RoundingMode.DOWN)

    /** Token amounts returned for [liquidityAmount], each truncated in favor of the pool. */
    fun withdrawal(
        liquidityAmount: BigDecimal,
        reserveA: BigDecimal,
        reserveB: BigDecimal,
        decimalsA: Int,
        decimalsB: Int,
    ): Pair<BigDecimal, BigDecimal> {
        val totalLiquidity = (reserveA * reserveB).sqrt(CALC)
        val ratio = liquidityAmount.divide(totalLiquidity, CALC)
        return (reserveA * ratio).setScale(decimalsA, RoundingMode.DOWN) to
            (reserveB * ratio).setScale(decimalsB, RoundingMode.DOWN)
    }

    /** Price of token A in token B. Display value. */
    fun price(
        reserveA: BigDecimal,
        reserveB: BigDecimal,
    ): Double = reserveB.divide(reserveA, CALC).toDouble()

    const val LIQUIDITY_DECIMALS = 18
}
