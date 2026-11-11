package io.github.gihoon1123.dexsimulator.shared.pool

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.time.Instant

// Field names follow the original TypeScript interfaces so the JSON stays the same.
// Token amounts are BigDecimal at token scale; prices, percentages and USD estimates are
// display values (Double). See docs/decisions.md #3.

data class PoolState(
    val address: String,
    val pair: String,
    val tokenA: String,
    val tokenB: String,
    val reserveA: BigDecimal,
    val reserveB: BigDecimal,
    val k: BigDecimal,
    val feeRate: BigDecimal,
    val createdAt: Instant,
    val lastUpdated: Instant,
    val totalVolume: BigDecimal,
    val volume24h: BigDecimal,
    val isActive: Boolean,
) {
    // Derived values, not part of the original JSON.
    @get:JsonIgnore
    val decimalsA: Int get() = Tokens.decimals(tokenA)

    @get:JsonIgnore
    val decimalsB: Int get() = Tokens.decimals(tokenB)

    fun price(): Double = AmmMath.price(reserveA, reserveB)

    fun hasToken(symbol: String): Boolean = tokenA == symbol || tokenB == symbol
}

data class CreatePoolParams(
    val address: String,
    val pair: String,
    val tokenA: String,
    val tokenB: String,
    val initialReserveA: BigDecimal,
    val initialReserveB: BigDecimal,
    val feeRate: BigDecimal,
)

data class PoolStats(
    val address: String,
    val pair: String,
    val currentPrice: Double,
    val priceChange24h: Double,
    val volume24h: BigDecimal,
    val totalVolume: BigDecimal,
    val totalLiquidity: Double,
    val isActive: Boolean,
    val lastUpdated: Instant,
)

enum class PoolSortBy(
    @get:JsonValue val wire: String,
) {
    VOLUME("volume"),
    LIQUIDITY("liquidity"),
    PRICE("price"),
    CREATED("created"),
    ;

    companion object {
        fun of(wire: String): PoolSortBy = entries.first { it.wire == wire }
    }
}

data class PoolListQuery(
    val activeOnly: Boolean = false,
    val token: String? = null,
    val sortBy: PoolSortBy? = null,
    val descending: Boolean = false,
    val limit: Int = 10,
    val offset: Int = 0,
)

data class PoolSearchResult(
    val pools: List<PoolState>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val hasNext: Boolean,
)

data class SwapParams(
    val poolAddress: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val amountOutMin: BigDecimal,
    val recipient: String,
    /** Unix seconds. */
    val deadline: Long,
    val gasPrice: Long,
    val gasLimit: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SwapResult(
    val swapId: String,
    val poolAddress: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val expectedAmountOut: BigDecimal,
    val slippage: Double,
    val fee: BigDecimal,
    val feeToken: String,
    val gasUsed: Long,
    val gasCost: Long,
    val timestamp: Instant,
    val success: Boolean,
    val error: String? = null,
    val txHash: String? = null,
)

data class SwapSimulation(
    val expectedAmountOut: BigDecimal,
    val maxSlippage: Double,
    val estimatedFee: BigDecimal,
    val priceImpact: Double,
    val isExecutable: Boolean,
    val warnings: List<String>,
    val errors: List<String>,
)

data class AddLiquidityParams(
    val poolAddress: String,
    val amountA: BigDecimal,
    val amountB: BigDecimal,
    val amountAMin: BigDecimal,
    val amountBMin: BigDecimal,
    val recipient: String,
    val deadline: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AddLiquidityResult(
    val liquidityAmount: BigDecimal,
    val actualAmountA: BigDecimal,
    val actualAmountB: BigDecimal,
    val timestamp: Instant,
    val success: Boolean,
    val error: String? = null,
)

data class RemoveLiquidityParams(
    val poolAddress: String,
    val liquidityAmount: BigDecimal,
    val amountAMin: BigDecimal,
    val amountBMin: BigDecimal,
    val recipient: String,
    val deadline: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RemoveLiquidityResult(
    val amountA: BigDecimal,
    val amountB: BigDecimal,
    val timestamp: Instant,
    val success: Boolean,
    val error: String? = null,
)

/** Error messages kept identical to the original. */
object PoolMessages {
    const val POOL_NOT_FOUND = "풀을 찾을 수 없습니다"
    const val MIN_OUTPUT_NOT_MET = "최소 출력 수량을 충족하지 않습니다"
    const val DEADLINE_EXPIRED = "거래 데드라인이 만료되었습니다"
    const val MIN_LIQUIDITY_NOT_MET = "최소 유동성 수량을 충족하지 않습니다"
    const val HIGH_PRICE_IMPACT = "높은 가격 임팩트가 예상됩니다"
    const val HIGH_SLIPPAGE = "높은 슬리피지가 예상됩니다"

    // Added in the migration (docs/decisions.md #9).
    const val TOKEN_NOT_IN_POOL = "풀에 없는 토큰입니다"
    const val POOL_ALREADY_EXISTS = "이미 존재하는 풀입니다"
    const val INSUFFICIENT_POOL_LIQUIDITY = "풀 유동성보다 많이 뺄 수 없습니다"
}
