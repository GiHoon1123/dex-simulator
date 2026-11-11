package io.github.gihoon1123.dexsimulator.contract

import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.pool.AmmMath
import io.github.gihoon1123.dexsimulator.shared.pool.Tokens
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.util.Locale

/**
 * Singleton contract holding every pool, ported from the original `SingletonContractService`.
 *
 * Swap math is the shared constant product ([AmmMath]) with the whole input staying in the
 * pool. [quote] and [apply] are split so that the router can check a whole multi-hop route
 * before changing any pool (decisions #14).
 */
@Service
class SingletonContractService(
    private val sim: SimulationContext,
    private val clock: Clock,
) {
    private val pools = LinkedHashMap<String, SingletonPool>()
    private var swapCounter = 0L

    /** USD volume per pool, priced by the input token of each swap. */
    private val volumeUsd = HashMap<String, Double>()

    @PostConstruct
    fun initializeDefaultPools() = runBlocking(sim.dispatcher) { createDefaults() }

    /** Throws [IllegalStateException] when the pair already exists, as the original did. */
    suspend fun createPool(params: CreateSingletonPoolParams): SingletonPool =
        sim.run {
            val poolId = poolIdOf(params.tokenA, params.tokenB)
            check(!pools.containsKey(poolId)) { "풀이 이미 존재합니다: $poolId" }
            require(params.initialReserveA.signum() > 0 && params.initialReserveB.signum() > 0) { "초기 준비금은 0보다 커야 합니다" }
            val reserveA = Tokens.scale(params.initialReserveA, params.tokenA)
            val reserveB = Tokens.scale(params.initialReserveB, params.tokenB)
            val now = clock.nowMillis()
            val pool =
                SingletonPool(
                    poolId = poolId,
                    tokenA = params.tokenA,
                    tokenB = params.tokenB,
                    reserveA = reserveA,
                    reserveB = reserveB,
                    k = reserveA * reserveB,
                    feeRate = params.feeRate,
                    currentPrice = AmmMath.price(reserveA, reserveB),
                    totalVolume = BigDecimal.ZERO,
                    swapCount = 0,
                    createdAt = now,
                    lastUpdated = now,
                )
            pools[poolId] = pool
            pool
        }

    suspend fun getPool(poolId: String): SingletonPool? = sim.run { pools[poolId] }

    suspend fun findPoolByTokens(
        tokenA: String,
        tokenB: String,
    ): SingletonPool? = sim.run { pools[poolIdOf(tokenA, tokenB)] }

    suspend fun getAllPools(
        token: String? = null,
        minLiquidity: Double? = null,
        sortBy: SingletonSortBy? = null,
        descending: Boolean = false,
    ): List<SingletonPool> =
        sim.run {
            var result = pools.values.toList()
            token?.let { t -> result = result.filter { it.tokenA == t || it.tokenB == t } }
            minLiquidity?.let { min -> result = result.filter { liquidityUsd(it) >= min } }
            sortBy?.let { sort ->
                val comparator: Comparator<SingletonPool> =
                    when (sort) {
                        SingletonSortBy.LIQUIDITY -> compareBy { liquidityUsd(it) }
                        SingletonSortBy.VOLUME -> compareBy { it.totalVolume }
                        SingletonSortBy.PRICE -> compareBy { it.currentPrice }
                        SingletonSortBy.CREATED -> compareBy { it.createdAt }
                    }
                result = result.sortedWith(if (descending) comparator.reversed() else comparator)
            }
            result
        }

    suspend fun simulateSwap(params: SingletonSwapParams): SingletonSwapSimulation = sim.run { quote(params) }

    suspend fun executeSwap(params: SingletonSwapParams): SingletonSwapResult =
        sim.run {
            val pool = pools[params.poolId] ?: return@run failed(params, EMPTY_STATE, NOT_FOUND)
            val simulation = quote(params)
            if (!simulation.isExecutable) return@run failed(params, snapshot(pool), simulation.errors.joinToString(", "))
            apply(params, simulation)
        }

    suspend fun analyzePriceImpact(
        poolId: String,
        tokenIn: String,
        amountIn: BigDecimal,
    ): PriceImpactAnalysis =
        sim.run {
            val pool = pools[poolId] ?: throw IllegalArgumentException(NOT_FOUND)
            val tokenOut = if (tokenIn == pool.tokenA) pool.tokenB else pool.tokenA
            val simulation = quote(SingletonSwapParams(poolId, tokenIn, tokenOut, amountIn))
            val (level, recommendation) =
                when {
                    simulation.priceImpact < 0.1 -> "low" to "안전한 거래량입니다"
                    simulation.priceImpact < 1 -> "medium" to "적정 거래량입니다"
                    simulation.priceImpact < 5 -> "high" to "거래량을 줄이는 것을 권장합니다"
                    else -> "extreme" to "거래량을 대폭 줄이거나 다른 경로를 찾으세요"
                }
            PriceImpactAnalysis(
                amountIn = amountIn,
                amountOut = simulation.expectedAmountOut,
                impact = simulation.priceImpact,
                level = level,
                currentPrice = simulation.priceBefore,
                expectedPrice = simulation.priceAfter,
                recommendation = recommendation,
            )
        }

    suspend fun getPoolStats(): SingletonPoolStats =
        sim.run {
            val all = pools.values.toList()
            val totalLiquidity = all.sumOf { liquidityUsd(it) }
            SingletonPoolStats(
                totalPools = all.size,
                totalLiquidity = totalLiquidity,
                totalVolume = volumeUsd.values.sum(),
                totalSwaps = all.sumOf { it.swapCount },
                averagePoolSize = if (all.isEmpty()) 0.0 else totalLiquidity / all.size,
                // First pool with the most swaps, like the original stable sort.
                mostActivePool = all.maxByOrNull { it.swapCount }?.poolId.orEmpty(),
            )
        }

    suspend fun resetPools() =
        sim.run {
            pools.clear()
            volumeUsd.clear()
            swapCounter = 0
            createDefaults()
        }

    /** Quote on the current pool state. Must be called on the simulation thread. */
    internal fun quote(params: SingletonSwapParams): SingletonSwapSimulation {
        val pool = pools[params.poolId] ?: return rejected(NOT_FOUND)
        val isTokenA = params.tokenIn == pool.tokenA
        if (!isTokenA && params.tokenIn != pool.tokenB) return rejected("잘못된 토큰 쌍입니다")

        val tokenOut = if (isTokenA) pool.tokenB else pool.tokenA
        val reserveIn = if (isTokenA) pool.reserveA else pool.reserveB
        val reserveOut = if (isTokenA) pool.reserveB else pool.reserveA
        val quote =
            AmmMath.quoteSwap(
                amountIn = Tokens.scale(params.amountIn, params.tokenIn),
                reserveIn = reserveIn,
                reserveOut = reserveOut,
                feeRate = pool.feeRate,
                decimalsIn = Tokens.decimals(params.tokenIn),
                decimalsOut = Tokens.decimals(tokenOut),
            )
        val priceBefore = reserveOut.divide(reserveIn, AmmMath.CALC).toDouble()
        val priceAfter = (reserveOut - quote.amountOut).divide(reserveIn + quote.amountInAfterFee, AmmMath.CALC).toDouble()

        val warnings = if (quote.priceImpact > HIGH_IMPACT_PERCENT) listOf("높은 가격 임팩트: ${format2(quote.priceImpact)}%") else emptyList()
        val errors =
            buildList {
                if (quote.priceImpact > params.slippageTolerance) {
                    add("슬리피지 초과: ${format2(quote.priceImpact)}% > ${plain(params.slippageTolerance)}%")
                }
                if (quote.amountOut < params.minAmountOut) {
                    add(
                        "최소 출력량 미달: ${quote.amountOut.setScale(
                            6,
                            java.math.RoundingMode.DOWN,
                        ).toPlainString()} < ${params.minAmountOut.stripTrailingZeros().toPlainString()}",
                    )
                }
                if (quote.amountOut > reserveOut * MAX_OUTPUT_SHARE) add("출력량이 풀 유동성의 90%를 초과합니다")
            }
        return SingletonSwapSimulation(
            expectedAmountOut = quote.amountOut,
            fee = quote.fee,
            amountInAfterFee = quote.amountInAfterFee,
            priceImpact = quote.priceImpact,
            priceBefore = priceBefore,
            priceAfter = priceAfter,
            isExecutable = errors.isEmpty(),
            warnings = warnings,
            errors = errors,
        )
    }

    /** Applies a swap quoted by [quote] on the current state. Must be called on the simulation thread. */
    internal fun apply(
        params: SingletonSwapParams,
        simulation: SingletonSwapSimulation,
    ): SingletonSwapResult {
        val pool = pools.getValue(params.poolId)
        val amountIn = Tokens.scale(params.amountIn, params.tokenIn)
        val isTokenA = params.tokenIn == pool.tokenA
        val reserveA = if (isTokenA) pool.reserveA + amountIn else pool.reserveA - simulation.expectedAmountOut
        val reserveB = if (isTokenA) pool.reserveB - simulation.expectedAmountOut else pool.reserveB + amountIn
        val now = clock.nowMillis()
        val updated =
            pool.copy(
                reserveA = reserveA,
                reserveB = reserveB,
                k = reserveA * reserveB,
                currentPrice = AmmMath.price(reserveA, reserveB),
                totalVolume = pool.totalVolume + amountIn,
                swapCount = pool.swapCount + 1,
                lastUpdated = now,
            )
        pools[pool.poolId] = updated
        volumeUsd.merge(pool.poolId, amountIn.toDouble() * usdPrice(params.tokenIn), Double::plus)
        return SingletonSwapResult(
            swapId = "swap_${++swapCounter}_${now.toEpochMilli()}",
            poolId = params.poolId,
            tokenIn = params.tokenIn,
            tokenOut = params.tokenOut,
            amountIn = amountIn,
            amountOut = simulation.expectedAmountOut,
            fee = simulation.fee,
            priceImpact = simulation.priceImpact,
            poolStateBefore = snapshot(pool),
            poolStateAfter = snapshot(updated),
            timestamp = now,
            success = true,
        )
    }

    /** Pools of the singleton, read on the simulation thread. */
    internal fun poolsSnapshot(): Collection<SingletonPool> = pools.values

    internal fun poolIdOf(
        tokenA: String,
        tokenB: String,
    ): String {
        val (first, second) = listOf(tokenA.lowercase(), tokenB.lowercase()).sorted()
        return "pool_${first}_$second"
    }

    private suspend fun createDefaults() {
        DEFAULT_POOLS.forEach { createPool(it) }
    }

    private fun liquidityUsd(pool: SingletonPool) =
        pool.reserveA.toDouble() * usdPrice(pool.tokenA) + pool.reserveB.toDouble() * usdPrice(pool.tokenB)

    private fun usdPrice(token: String) = USD_PRICES[token] ?: 1.0

    private fun snapshot(pool: SingletonPool) = PoolStateSnapshot(pool.reserveA, pool.reserveB, pool.k, pool.currentPrice)

    private fun rejected(error: String) =
        SingletonSwapSimulation(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, 0.0, false, emptyList(), listOf(error))

    private fun failed(
        params: SingletonSwapParams,
        state: PoolStateSnapshot,
        error: String,
    ) = SingletonSwapResult(
        swapId = "",
        poolId = params.poolId,
        tokenIn = params.tokenIn,
        tokenOut = params.tokenOut,
        amountIn = BigDecimal.ZERO,
        amountOut = BigDecimal.ZERO,
        fee = BigDecimal.ZERO,
        priceImpact = 0.0,
        poolStateBefore = state,
        poolStateAfter = state,
        timestamp = clock.nowMillis(),
        success = false,
        error = error,
    )

    companion object {
        const val NOT_FOUND = "풀을 찾을 수 없습니다"
        private const val HIGH_IMPACT_PERCENT = 5.0
        private val MAX_OUTPUT_SHARE = BigDecimal("0.9")
        private val EMPTY_STATE = PoolStateSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0)

        private val USD_PRICES = mapOf("ETH" to 2000.0, "BTC" to 40000.0, "USDC" to 1.0, "DAI" to 1.0, "WETH" to 2000.0, "USDT" to 1.0)

        private fun pool(
            a: String,
            b: String,
            reserveA: String,
            reserveB: String,
            fee: String,
        ) = CreateSingletonPoolParams(a, b, BigDecimal(reserveA), BigDecimal(reserveB), BigDecimal(fee))

        val DEFAULT_POOLS =
            listOf(
                pool("ETH", "USDC", "10000", "20000000", "0.003"),
                pool("BTC", "USDC", "500", "20000000", "0.003"),
                pool("BTC", "ETH", "250", "5000", "0.003"),
                pool("USDC", "DAI", "10000000", "10000000", "0.0001"),
                pool("USDC", "USDT", "5000000", "5000000", "0.0001"),
                pool("DAI", "USDT", "3000000", "3000000", "0.0001"),
                pool("ETH", "DAI", "500", "1000000", "0.003"),
                pool("BTC", "DAI", "25", "1000000", "0.003"),
                pool("WETH", "USDC", "2000", "4000000", "0.0005"),
                pool("WETH", "ETH", "1000", "1000", "0.0001"),
            )

        fun format2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        /** Number as JavaScript prints it: 0.5, 100. */
        fun plain(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }
}
