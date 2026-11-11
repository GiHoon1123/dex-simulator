package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.events.LiquidityAddedEvent
import io.github.gihoon1123.dexsimulator.shared.events.LiquidityRemovedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolChanges
import io.github.gihoon1123.dexsimulator.shared.events.PoolCreatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolCreationMetadata
import io.github.gihoon1123.dexsimulator.shared.events.PoolStatsUpdatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolTransaction
import io.github.gihoon1123.dexsimulator.shared.events.PoolTransactionCreatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolUpdateReason
import io.github.gihoon1123.dexsimulator.shared.events.PoolUpdatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.SwapCallParams
import io.github.gihoon1123.dexsimulator.shared.events.SwapExecutedEvent
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

/**
 * Constant product pools, ported from the original `PoolService`.
 *
 * All public functions run on the simulation thread ([SimulationContext]), so the plain
 * maps below are only touched by one thread. Pool states are immutable and replaced on
 * every change.
 */
@Service
class PoolService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pools = LinkedHashMap<String, PoolState>()
    private val poolStats = LinkedHashMap<String, PoolStats>()
    private var swapCounter = 0L

    @PostConstruct
    fun initializeDefaultPools() =
        runBlocking(sim.dispatcher) {
            DEFAULT_POOLS.forEach { createPool(it) }
        }

    suspend fun createPool(params: CreatePoolParams): PoolState =
        sim.run {
            if (pools.containsKey(params.address)) {
                throw ApiException(HttpStatus.CONFLICT, PoolMessages.POOL_ALREADY_EXISTS)
            }
            val now = clock.nowMillis()
            val reserveA = Tokens.scale(params.initialReserveA, params.tokenA)
            val reserveB = Tokens.scale(params.initialReserveB, params.tokenB)
            val pool =
                PoolState(
                    address = params.address,
                    pair = params.pair,
                    tokenA = params.tokenA,
                    tokenB = params.tokenB,
                    reserveA = reserveA,
                    reserveB = reserveB,
                    k = reserveA * reserveB,
                    feeRate = params.feeRate,
                    createdAt = now,
                    lastUpdated = now,
                    totalVolume = BigDecimal.ZERO,
                    volume24h = BigDecimal.ZERO,
                    isActive = true,
                )
            pools[pool.address] = pool
            poolStats[pool.address] = statsOf(pool, priceChange24h = 0.0)
            events.publish(
                PoolCreatedEvent(
                    eventId = EventIds.next(now),
                    pool = pool,
                    timestamp = now,
                    metadata = PoolCreationMetadata("system", totalLiquidityUsd(pool), 0),
                ),
            )
            log.info("풀 생성됨: {} ({})", pool.address, pool.pair)
            pool
        }

    suspend fun getPool(poolAddress: String): PoolState? = sim.run { pools[poolAddress] }

    suspend fun getAllPools(): List<PoolState> = sim.run { pools.values.toList() }

    suspend fun searchPools(query: PoolListQuery): PoolSearchResult =
        sim.run {
            var result = pools.values.toList()
            if (query.activeOnly) result = result.filter { it.isActive }
            query.token?.let { token -> result = result.filter { it.hasToken(token) } }
            query.sortBy?.let { sortBy ->
                val comparator = comparatorFor(sortBy)
                result = result.sortedWith(if (query.descending) comparator.reversed() else comparator)
            }
            val total = result.size
            PoolSearchResult(
                pools = result.drop(query.offset).take(query.limit),
                total = total,
                page = query.offset / query.limit + 1,
                pageSize = query.limit,
                hasNext = query.offset + query.limit < total,
            )
        }

    suspend fun simulateSwap(params: SwapParams): SwapSimulation =
        sim.run {
            val pool = pools[params.poolAddress] ?: return@run failedSimulation(PoolMessages.POOL_NOT_FOUND)
            if (!pool.hasToken(params.tokenIn)) return@run failedSimulation(PoolMessages.TOKEN_NOT_IN_POOL)
            simulate(pool, params)
        }

    suspend fun executeSwap(params: SwapParams): SwapResult =
        sim.run {
            val pool = pools[params.poolAddress] ?: return@run failedSwap(params, PoolMessages.POOL_NOT_FOUND)
            if (!pool.hasToken(params.tokenIn)) return@run failedSwap(params, PoolMessages.TOKEN_NOT_IN_POOL)
            val simulation = simulate(pool, params)
            if (!simulation.isExecutable) return@run failedSwap(params, simulation.errors.joinToString(", "))

            val now = clock.nowMillis()
            val swapId = "swap_${++swapCounter}_${now.toEpochMilli()}"
            val amountIn = Tokens.scale(params.amountIn, params.tokenIn)
            val amountOut = simulation.expectedAmountOut
            val isTokenA = params.tokenIn == pool.tokenA
            val reserveA = if (isTokenA) pool.reserveA + amountIn else pool.reserveA - amountOut
            val reserveB = if (isTokenA) pool.reserveB - amountOut else pool.reserveB + amountIn
            val updated =
                pool.copy(
                    reserveA = reserveA,
                    reserveB = reserveB,
                    k = reserveA * reserveB,
                    lastUpdated = now,
                    totalVolume = pool.totalVolume + amountIn,
                    volume24h = pool.volume24h + amountIn,
                )
            pools[pool.address] = updated

            val result =
                SwapResult(
                    swapId = swapId,
                    poolAddress = params.poolAddress,
                    tokenIn = params.tokenIn,
                    tokenOut = params.tokenOut,
                    amountIn = amountIn,
                    amountOut = amountOut,
                    expectedAmountOut = amountOut,
                    slippage = simulation.maxSlippage,
                    fee = simulation.estimatedFee,
                    feeToken = params.tokenIn,
                    gasUsed = params.gasLimit,
                    gasCost = params.gasLimit * params.gasPrice,
                    timestamp = now,
                    success = true,
                )
            publishSwapTransaction(params, result, updated)
            events.publish(SwapExecutedEvent(EventIds.next(now), result, updated, now))
            publishPoolUpdated(pool, updated, PoolUpdateReason.SWAP)
            log.info(
                "스왑 실행됨: {} - {} {} → {} {}",
                swapId,
                amountIn.toPlainString(),
                params.tokenIn,
                amountOut.toPlainString(),
                params.tokenOut,
            )
            result
        }

    suspend fun addLiquidity(params: AddLiquidityParams): AddLiquidityResult =
        sim.run {
            val now = clock.nowMillis()
            val pool = pools[params.poolAddress] ?: return@run failedAdd(now, PoolMessages.POOL_NOT_FOUND)
            val (actualA, actualB) =
                AmmMath.matchPoolRatio(
                    amountA = Tokens.scale(params.amountA, pool.tokenA),
                    amountB = Tokens.scale(params.amountB, pool.tokenB),
                    reserveA = pool.reserveA,
                    reserveB = pool.reserveB,
                    decimalsA = pool.decimalsA,
                    decimalsB = pool.decimalsB,
                )
            if (actualA < params.amountAMin || actualB < params.amountBMin) {
                return@run failedAdd(now, PoolMessages.MIN_LIQUIDITY_NOT_MET)
            }

            val reserveA = pool.reserveA + actualA
            val reserveB = pool.reserveB + actualB
            val updated = pool.copy(reserveA = reserveA, reserveB = reserveB, k = reserveA * reserveB, lastUpdated = now)
            pools[pool.address] = updated

            val result = AddLiquidityResult(AmmMath.liquidity(actualA, actualB), actualA, actualB, now, success = true)
            events.publish(LiquidityAddedEvent(EventIds.next(now), result, updated, now))
            publishPoolUpdated(pool, updated, PoolUpdateReason.LIQUIDITY_ADD)
            log.info("유동성 추가됨: {} - {} {} + {} {}", pool.address, actualA, pool.tokenA, actualB, pool.tokenB)
            result
        }

    suspend fun removeLiquidity(params: RemoveLiquidityParams): RemoveLiquidityResult =
        sim.run {
            val now = clock.nowMillis()
            val pool = pools[params.poolAddress] ?: return@run failedRemove(now, PoolMessages.POOL_NOT_FOUND)
            val (amountA, amountB) =
                AmmMath.withdrawal(
                    liquidityAmount = params.liquidityAmount,
                    reserveA = pool.reserveA,
                    reserveB = pool.reserveB,
                    decimalsA = pool.decimalsA,
                    decimalsB = pool.decimalsB,
                )
            // The original let reserves reach zero or below, which breaks every price.
            if (amountA >= pool.reserveA || amountB >= pool.reserveB) {
                return@run failedRemove(now, PoolMessages.INSUFFICIENT_POOL_LIQUIDITY)
            }
            if (amountA < params.amountAMin || amountB < params.amountBMin) {
                return@run failedRemove(now, PoolMessages.MIN_OUTPUT_NOT_MET)
            }

            val reserveA = pool.reserveA - amountA
            val reserveB = pool.reserveB - amountB
            val updated = pool.copy(reserveA = reserveA, reserveB = reserveB, k = reserveA * reserveB, lastUpdated = now)
            pools[pool.address] = updated

            val result = RemoveLiquidityResult(amountA, amountB, now, success = true)
            events.publish(LiquidityRemovedEvent(EventIds.next(now), result, updated, now))
            publishPoolUpdated(pool, updated, PoolUpdateReason.LIQUIDITY_REMOVE)
            log.info("유동성 제거됨: {} - {} {} + {} {}", pool.address, amountA, pool.tokenA, amountB, pool.tokenB)
            result
        }

    suspend fun getPoolStats(poolAddress: String): PoolStats? = sim.run { poolStats[poolAddress] }

    suspend fun getAllPoolStats(): List<PoolStats> = sim.run { poolStats.values.toList() }

    /** Refreshes the stats snapshot of a pool. No caller in the original either. */
    suspend fun updatePoolStats(poolAddress: String) =
        sim.run {
            val pool = pools[poolAddress] ?: return@run
            val stats = poolStats[poolAddress] ?: return@run
            val updated = statsOf(pool, stats.priceChange24h)
            poolStats[poolAddress] = updated
            events.publish(
                PoolStatsUpdatedEvent(EventIds.next(updated.lastUpdated), poolAddress, updated, updated.lastUpdated, 300),
            )
        }

    private fun simulate(
        pool: PoolState,
        params: SwapParams,
    ): SwapSimulation {
        val isTokenA = params.tokenIn == pool.tokenA
        val tokenOut = if (isTokenA) pool.tokenB else pool.tokenA
        val quote =
            AmmMath.quoteSwap(
                amountIn = Tokens.scale(params.amountIn, params.tokenIn),
                reserveIn = if (isTokenA) pool.reserveA else pool.reserveB,
                reserveOut = if (isTokenA) pool.reserveB else pool.reserveA,
                feeRate = pool.feeRate,
                decimalsIn = Tokens.decimals(params.tokenIn),
                decimalsOut = Tokens.decimals(tokenOut),
            )
        val slippage = AmmMath.slippagePercent(params.amountOutMin, quote.amountOut)
        val meetsMinimum = quote.amountOut >= params.amountOutMin
        val beforeDeadline = clock.millis() < params.deadline * 1000

        val warnings =
            buildList {
                if (quote.priceImpact > HIGH_WARNING_PERCENT) add(PoolMessages.HIGH_PRICE_IMPACT)
                if (slippage > HIGH_WARNING_PERCENT) add(PoolMessages.HIGH_SLIPPAGE)
            }
        val errors =
            buildList {
                if (!meetsMinimum) add(PoolMessages.MIN_OUTPUT_NOT_MET)
                if (!beforeDeadline) add(PoolMessages.DEADLINE_EXPIRED)
            }
        return SwapSimulation(
            expectedAmountOut = quote.amountOut,
            maxSlippage = kotlin.math.abs(slippage),
            estimatedFee = quote.fee,
            priceImpact = quote.priceImpact,
            isExecutable = meetsMinimum && beforeDeadline,
            warnings = warnings,
            errors = errors,
        )
    }

    private suspend fun publishSwapTransaction(
        params: SwapParams,
        result: SwapResult,
        pool: PoolState,
    ) {
        val transaction =
            PoolTransaction(
                id = result.swapId,
                from = params.recipient,
                to = params.poolAddress,
                value = "0",
                swapParams =
                    SwapCallParams(
                        recipient = params.recipient,
                        zeroForOne = params.tokenIn == pool.tokenA,
                        amountSpecified = result.amountIn.stripTrailingZeros().toPlainString(),
                        sqrtPriceLimitX96 = "0",
                        data = "0x",
                    ),
                gasPrice = params.gasPrice,
                gasLimit = params.gasLimit,
                // Temporary nonce, as in the original. The blockchain module assigns real ones.
                nonce = Random.nextLong(TEMP_NONCE_BOUND),
            )
        events.publish(PoolTransactionCreatedEvent(EventIds.next(result.timestamp), transaction, params.poolAddress, result.timestamp))
    }

    private suspend fun publishPoolUpdated(
        previous: PoolState,
        current: PoolState,
        reason: PoolUpdateReason,
    ) {
        val now = current.lastUpdated
        events.publish(
            PoolUpdatedEvent(
                eventId = EventIds.next(now),
                poolAddress = current.address,
                previousState = previous,
                currentState = current,
                changes = PoolChanges.between(previous, current),
                timestamp = now,
                reason = reason,
            ),
        )
    }

    private fun statsOf(
        pool: PoolState,
        priceChange24h: Double,
    ) = PoolStats(
        address = pool.address,
        pair = pool.pair,
        currentPrice = pool.price(),
        priceChange24h = priceChange24h,
        volume24h = pool.volume24h,
        totalVolume = pool.totalVolume,
        totalLiquidity = totalLiquidityUsd(pool),
        isActive = pool.isActive,
        lastUpdated = clock.nowMillis(),
    )

    private fun comparatorFor(sortBy: PoolSortBy): Comparator<PoolState> =
        when (sortBy) {
            PoolSortBy.VOLUME -> compareBy { it.totalVolume }
            PoolSortBy.LIQUIDITY -> compareBy { totalLiquidityUsd(it) }
            PoolSortBy.PRICE -> compareBy { it.price() }
            PoolSortBy.CREATED -> compareBy { it.createdAt }
        }

    private fun failedSimulation(error: String) =
        SwapSimulation(BigDecimal.ZERO, 0.0, BigDecimal.ZERO, 0.0, isExecutable = false, warnings = emptyList(), errors = listOf(error))

    private fun failedSwap(
        params: SwapParams,
        error: String,
    ) = SwapResult(
        swapId = "",
        poolAddress = params.poolAddress,
        tokenIn = params.tokenIn,
        tokenOut = params.tokenOut,
        amountIn = BigDecimal.ZERO,
        amountOut = BigDecimal.ZERO,
        expectedAmountOut = BigDecimal.ZERO,
        slippage = 0.0,
        fee = BigDecimal.ZERO,
        feeToken = params.tokenIn,
        gasUsed = 0,
        gasCost = 0,
        timestamp = clock.nowMillis(),
        success = false,
        error = error,
    )

    private fun failedAdd(
        now: Instant,
        error: String,
    ) = AddLiquidityResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, now, success = false, error = error)

    private fun failedRemove(
        now: Instant,
        error: String,
    ) = RemoveLiquidityResult(BigDecimal.ZERO, BigDecimal.ZERO, now, success = false, error = error)

    companion object {
        private const val HIGH_WARNING_PERCENT = 5.0
        private const val TEMP_NONCE_BOUND = 1_000_000L

        /** Mock USD prices used only to estimate pool liquidity, as in the original. */
        private val MOCK_USD_PRICES =
            mapOf("ETH" to 2000.0, "BTC" to 40000.0, "USDC" to 1.0, "DAI" to 1.0, "WETH" to 2000.0)

        fun totalLiquidityUsd(pool: PoolState): Double =
            pool.reserveA.toDouble() * (MOCK_USD_PRICES[pool.tokenA] ?: 1.0) +
                pool.reserveB.toDouble() * (MOCK_USD_PRICES[pool.tokenB] ?: 1.0)

        val DEFAULT_POOLS =
            listOf(
                CreatePoolParams(
                    address = "0x8ad599c3A0ff1De082011EFDDc58f1908eb6e6D8",
                    pair = "ETH/USDC",
                    tokenA = "ETH",
                    tokenB = "USDC",
                    initialReserveA = BigDecimal("1000"),
                    initialReserveB = BigDecimal("2000000"),
                    feeRate = BigDecimal("0.003"),
                ),
                CreatePoolParams(
                    address = "0x4585FE77225b41b697C938B018E2Ac67Ac5a20c0",
                    pair = "BTC/ETH",
                    tokenA = "BTC",
                    tokenB = "ETH",
                    initialReserveA = BigDecimal("50"),
                    initialReserveB = BigDecimal("1000"),
                    feeRate = BigDecimal("0.003"),
                ),
                CreatePoolParams(
                    address = "0x5777d92f208679DB4b9778590Fa3CAB3aC9e2168",
                    pair = "DAI/USDC",
                    tokenA = "DAI",
                    tokenB = "USDC",
                    initialReserveA = BigDecimal("1000000"),
                    initialReserveB = BigDecimal("1000000"),
                    feeRate = BigDecimal("0.0001"),
                ),
                CreatePoolParams(
                    address = "0x88e6A0c2dDD26FEEb64F039a2c41296FcB3f5640",
                    pair = "WETH/USDC",
                    tokenA = "WETH",
                    tokenB = "USDC",
                    initialReserveA = BigDecimal("1000"),
                    initialReserveB = BigDecimal("2000000"),
                    feeRate = BigDecimal("0.0005"),
                ),
            )
    }
}
