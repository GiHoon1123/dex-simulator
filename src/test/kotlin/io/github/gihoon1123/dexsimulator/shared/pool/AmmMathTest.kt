package io.github.gihoon1123.dexsimulator.shared.pool

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected values were computed separately with Python `decimal` at 50 digits.
class AmmMathTest {
    private fun dec(value: String) = BigDecimal(value)

    @Test
    fun `swap quote keeps the fee in the pool and truncates the output`() {
        val quote = AmmMath.quoteSwap(dec("1").setScale(18), dec("1000"), dec("2000000"), dec("0.003"), 18, 6)

        assertEquals(dec("0.997000000000000000"), quote.amountInAfterFee)
        assertEquals(dec("0.003000000000000000"), quote.fee)
        // Exact value is 1992.0139620...; truncated to USDC's 6 decimals.
        assertEquals(dec("1992.013962"), quote.amountOut)
        assertEquals(0.19910219321336627, quote.priceImpact, 1e-12)
    }

    @Test
    fun `swap in the other direction uses the other token's decimals`() {
        val quote = AmmMath.quoteSwap(dec("2000").setScale(6), dec("2000000"), dec("1000"), dec("0.003"), 6, 18)

        assertEquals(dec("1994.000000"), quote.amountInAfterFee)
        assertEquals(dec("0.996006981039903216"), quote.amountOut)
    }

    @Test
    fun `fee and amount after fee always add up to the input`() {
        val amountIn = dec("0.123456789012345678")
        val quote = AmmMath.quoteSwap(amountIn, dec("1000"), dec("2000000"), dec("0.003"), 18, 6)

        assertEquals(amountIn, quote.amountInAfterFee + quote.fee)
    }

    @Test
    fun `zero input quotes zero output`() {
        val quote = AmmMath.quoteSwap(dec("0").setScale(18), dec("1000"), dec("2000000"), dec("0.003"), 18, 6)

        assertEquals(0, quote.amountOut.signum())
        assertEquals(0.0, quote.priceImpact)
    }

    @Test
    fun `slippage is zero when nothing comes out instead of NaN`() {
        assertEquals(0.0, AmmMath.slippagePercent(dec("1"), BigDecimal.ZERO))
        assertEquals(-50.0, AmmMath.slippagePercent(dec("50"), dec("100")))
    }

    @Test
    fun `deposit is reduced on the side that exceeds the pool ratio`() {
        val (a, b) = AmmMath.matchPoolRatio(dec("10"), dec("30000"), dec("1000"), dec("2000000"), 18, 6)
        assertEquals(dec("10"), a)
        assertEquals(dec("20000.000000"), b)

        val (a2, b2) = AmmMath.matchPoolRatio(dec("10"), dec("10000"), dec("1000"), dec("2000000"), 18, 6)
        assertEquals(dec("5.000000000000000000"), a2)
        assertEquals(dec("10000"), b2)
    }

    @Test
    fun `deposit with a zero side does not divide by zero`() {
        val (a, b) = AmmMath.matchPoolRatio(dec("10"), BigDecimal.ZERO, dec("1000"), dec("2000000"), 18, 6)

        assertEquals(0, a.signum())
        assertEquals(0, b.signum())
    }

    @Test
    fun `liquidity is the geometric mean of the deposit`() {
        assertEquals(dec("447.213595499957939281"), AmmMath.liquidity(dec("10"), dec("20000")))
    }

    @Test
    fun `withdrawal is proportional and truncated in favor of the pool`() {
        val (a, b) = AmmMath.withdrawal(dec("100"), dec("1000"), dec("2000000"), 18, 6)

        assertEquals(dec("2.236067977499789696"), a)
        assertEquals(dec("4472.135954"), b)
        assertTrue(a.scale() == 18 && b.scale() == 6)
    }

    @Test
    fun `token amounts are truncated to the token decimals`() {
        assertEquals(dec("1.123456"), Tokens.scale(dec("1.1234569"), "USDC"))
        assertEquals(8, Tokens.decimals("BTC"))
        assertEquals(Tokens.DEFAULT_DECIMALS, Tokens.decimals("UNKNOWN"))
    }
}
