package io.github.gihoon1123.dexsimulator.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
import java.time.Instant

// Singleton (Uniswap V4 style, all pools in one contract) and router models with the
// original field names. Amounts are BigDecimal at token scale; prices and impacts are Double.

data class SingletonPool(
    val poolId: String,
    val tokenA: String,
    val tokenB: String,
    val reserveA: BigDecimal,
    val reserveB: BigDecimal,
    val k: BigDecimal,
    val feeRate: BigDecimal,
    val currentPrice: Double,
    val totalVolume: BigDecimal,
    val swapCount: Int,
    val createdAt: Instant,
    val lastUpdated: Instant,
)

data class CreateSingletonPoolParams(
    val tokenA: String,
    val tokenB: String,
    val initialReserveA: BigDecimal,
    val initialReserveB: BigDecimal,
    val feeRate: BigDecimal,
)

data class SingletonSwapParams(
    val poolId: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val minAmountOut: BigDecimal = BigDecimal.ZERO,
    val recipient: String = "0x0",
    /** Percent. */
    val slippageTolerance: Double = 100.0,
)

data class SingletonSwapSimulation(
    val expectedAmountOut: BigDecimal,
    val fee: BigDecimal,
    val amountInAfterFee: BigDecimal,
    val priceImpact: Double,
    val priceBefore: Double,
    val priceAfter: Double,
    val isExecutable: Boolean,
    val warnings: List<String>,
    val errors: List<String>,
)

data class PoolStateSnapshot(
    val reserveA: BigDecimal,
    val reserveB: BigDecimal,
    val k: BigDecimal,
    val price: Double,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SingletonSwapResult(
    val swapId: String,
    val poolId: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val fee: BigDecimal,
    val priceImpact: Double,
    val poolStateBefore: PoolStateSnapshot,
    val poolStateAfter: PoolStateSnapshot,
    val timestamp: Instant,
    val success: Boolean,
    val error: String? = null,
)

enum class SingletonSortBy(
    @get:JsonValue val wire: String,
) {
    LIQUIDITY("liquidity"),
    VOLUME("volume"),
    PRICE("price"),
    CREATED("created"),
}

data class SingletonPoolStats(
    val totalPools: Int,
    /** USD at mock prices. */
    val totalLiquidity: Double,
    /** USD at mock prices. */
    val totalVolume: Double,
    val totalSwaps: Int,
    val averagePoolSize: Double,
    val mostActivePool: String,
)

data class PriceImpactAnalysis(
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val impact: Double,
    val level: String,
    val currentPrice: Double,
    val expectedPrice: Double,
    val recommendation: String,
)

data class HopDetail(
    val poolId: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val priceImpact: Double,
)

enum class RouteType(
    @get:JsonValue val wire: String,
) {
    DIRECT("direct"),
    MULTI_HOP("multi-hop"),
}

data class Route(
    val routeId: String,
    val path: List<String>,
    val pools: List<String>,
    val type: RouteType,
    val hops: Int,
    val expectedAmountOut: BigDecimal,
    /** Sum of the hops' impacts, as in the original. */
    val priceImpact: Double,
    val hopsDetail: List<HopDetail>,
    val gasEstimate: Long,
    /** Sum of each hop's fee in that hop's input token (mixed units, as in the original). */
    val totalFee: BigDecimal,
    val feasible: Boolean,
    val warnings: List<String>,
    val errors: List<String>,
    val recommended: Boolean,
    val reason: String,
)

data class RouteSearchOptions(
    val maxHops: Int? = null,
    val slippageTolerance: Double? = null,
    val gasPrice: Long? = null,
    val minPoolLiquidity: Double? = null,
)

data class AutoSwapParams(
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val minAmountOut: BigDecimal? = null,
    val slippageTolerance: Double,
    val recipient: String,
    val options: RouteSearchOptions? = null,
)

data class ExecutedHop(
    val poolId: String,
    val swapId: String,
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val priceImpact: Double,
    val timestamp: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AutoSwapResult(
    val swapId: String,
    /** Null when no route exists (the original returned an empty object). */
    val routeUsed: Route?,
    val hopsExecuted: List<ExecutedHop>,
    val finalAmountOut: BigDecimal,
    val totalPriceImpact: Double,
    val totalGasUsed: Long,
    val totalFee: BigDecimal,
    val executionTime: Long,
    val success: Boolean,
    val timestamp: Instant,
    val error: String? = null,
)

data class RouteDiff(
    val outputDiff: BigDecimal,
    val outputDiffPercent: Double,
    val impactDiff: Double,
    val gasDiff: Long,
    val recommendation: RouteType,
    val reason: String,
)

data class SingletonAdvantage(
    val singletonGas: Long,
    val regularDexGas: Long,
    val gasSaved: Long,
    val gasSavedPercent: Double,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RouteComparison(
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val allRoutes: List<Route>,
    val bestRoute: Route,
    val directRoute: Route?,
    val bestMultiHopRoute: Route?,
    val comparison: RouteDiff?,
    val singletonAdvantage: SingletonAdvantage,
)

data class RouteSearchResult(
    val tokenIn: String,
    val tokenOut: String,
    val amountIn: BigDecimal,
    val routes: List<Route>,
    val totalRoutes: Int,
    val directRoutes: Int,
    val twoHopRoutes: Int,
    val threeHopRoutes: Int,
    val searchTime: Long,
)
