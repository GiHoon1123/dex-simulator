package io.github.gihoon1123.dexsimulator.dex.lp

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.dex.Asset
import io.github.gihoon1123.dexsimulator.dex.LpPool
import io.github.gihoon1123.dexsimulator.dex.LpUser
import io.github.gihoon1123.dexsimulator.dex.PriceChangeEvent
import io.github.gihoon1123.dexsimulator.dex.TradeExecutedEvent
import io.github.gihoon1123.dexsimulator.dex.VolatilityMetrics
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Liquidity providers of the ETH/BTC pool, ported from the original `LpService`.
 *
 * Trades are applied through [applyTrade], called by the trader inside the same
 * simulation step. The original updated the pool from the `trade.executed` listener; with
 * an asynchronous bus another trade could read the pool before that update (decisions #11).
 * Fee and token payouts are truncated so that they never add up to more than was paid.
 */
@Service
class LpService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val random: Random,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var pool = emptyPool()

    @PostConstruct
    fun subscribe() {
        events.subscribe<PriceChangeEvent>(sim.scope) { handleMarketPriceChange(it) }
    }

    /** Creates 10 users with random shares of 1000 ETH and 30000 BTC. Replaces any existing pool. */
    suspend fun initLiquidity(): LpPool =
        sim.run {
            val ratios = ArrayList<Double>()
            var remaining = 1.0
            repeat(INITIAL_USERS - 1) {
                val ratio = random.nextDouble() * remaining * MAX_INITIAL_SHARE_OF_REMAINING
                ratios += round(ratio, 3)
                remaining -= ratio
            }
            ratios += round(remaining, 3)

            val users =
                ratios.mapIndexed { index, ratio ->
                    newUser(index + 1, scale3(TARGET_ETH * ratio.toBigDecimal()), scale3(TARGET_BTC * ratio.toBigDecimal()))
                }
            val initialValue = poolValue(TARGET_ETH, TARGET_BTC)
            pool =
                LpPool(
                    eth = TARGET_ETH,
                    btc = TARGET_BTC,
                    k = TARGET_ETH * TARGET_BTC,
                    feeRate = BASE_FEE_RATE,
                    userCount = users.size,
                    users = users,
                    initialPoolValue = initialValue,
                    currentPoolValue = initialValue,
                    poolSizeRatio = 1.0,
                    volatility = VolatilityMetrics(0.0, 0.0, 0.0),
                    lastVolatilityUpdate = clock.nowMillis(),
                )
            pool =
                withShares(pool).let { shared ->
                    shared.copy(
                        users =
                            shared.users.map {
                                it.copy(
                                    governanceTokens = (it.share * 100).toBigDecimal().setScale(2, RoundingMode.DOWN),
                                )
                            },
                    )
                }
            pool
        }

    suspend fun getPool(): LpPool = sim.run { requireInitialized() }

    suspend fun isInitialized(): Boolean = sim.run { pool.users.isNotEmpty() }

    /** Adds 1..5 users (up to 30 in total), each depositing 1~4% of the pool. */
    suspend fun addRandomUser(): LpPool =
        sim.run {
            val current = requireInitialized()
            if (current.users.size >= MAX_USERS) throw badRequest("최대 유저 수(${MAX_USERS}명)에 도달했습니다.")
            val toAdd = random.nextInt(min(MAX_USERS_PER_ADD, MAX_USERS - current.users.size)) + 1

            var eth = current.eth
            var btc = current.btc
            val users = current.users.toMutableList()
            repeat(toAdd) {
                val ratio = (random.nextDouble() * 0.03 + 0.01).toBigDecimal()
                val user = newUser(users.maxOf { it.id } + 1, scale3(eth * ratio), scale3(btc * ratio))
                users += user
                eth += user.eth
                btc += user.btc
            }
            pool = recalculated(current.copy(eth = eth, btc = btc, k = eth * btc, users = users, userCount = users.size))
            pool
        }

    /** Removes 1..3 random users (down to 10), taking their deposits out of the pool. */
    suspend fun removeRandomUser(): LpPool =
        sim.run {
            val current = requireInitialized()
            if (current.users.size <= MIN_USERS) throw badRequest("최소 유저 수(${MIN_USERS}명)에 도달했습니다.")
            val toRemove = random.nextInt(min(MAX_USERS_PER_REMOVE, current.users.size - MIN_USERS)) + 1

            var eth = current.eth
            var btc = current.btc
            val users = current.users.toMutableList()
            repeat(toRemove) {
                val removed = users.removeAt(random.nextInt(users.size))
                eth -= removed.eth
                btc -= removed.btc
            }
            pool = recalculated(current.copy(eth = eth, btc = btc, k = eth * btc, users = users, userCount = users.size))
            pool
        }

    /** Fee rate from pool size: smaller pool, higher fee, within 0.05%..1%. */
    suspend fun calculateDynamicFee(): Double =
        sim.run {
            pool = withSizeFee(requireInitialized())
            pool.feeRate
        }

    /** Fee for [amountIn] at the current dynamic rate, truncated to [decimals]. */
    suspend fun calculateFee(
        amountIn: BigDecimal,
        decimals: Int,
    ): BigDecimal = (amountIn * calculateDynamicFee().toBigDecimal()).setScale(decimals, RoundingMode.DOWN)

    /** Applies a trade to the reserves and pays the fee and governance tokens to the users. */
    suspend fun applyTrade(trade: TradeExecutedEvent) =
        sim.run {
            val current = requireInitialized()
            log.debug("거래 실행됨: {}, 수수료: {}", trade.tradeId, trade.fee.toPlainString())
            val updated = recalculated(current.copy(eth = trade.poolAfter.eth, btc = trade.poolAfter.btc, k = trade.poolAfter.k))
            val tokens = trade.fee * TOKENS_PER_FEE
            pool =
                updated.copy(
                    users =
                        updated.users.map { user ->
                            val share = user.share.toBigDecimal()
                            val userFee = (trade.fee * share).setScale(FEE_DECIMALS, RoundingMode.DOWN)
                            user.copy(
                                earnedEth = if (trade.from == Asset.ETH) user.earnedEth + userFee else user.earnedEth,
                                earnedBtc = if (trade.from == Asset.BTC) user.earnedBtc + userFee else user.earnedBtc,
                                governanceTokens = user.governanceTokens + (tokens * share).setScale(2, RoundingMode.DOWN),
                            )
                        },
                )
        }

    /** `market.price.changed`: records volatility and folds it into the fee. Ignored before init. */
    suspend fun handleMarketPriceChange(event: PriceChangeEvent) =
        sim.run {
            if (pool.users.isEmpty()) return@run
            val withVolatility =
                pool.copy(
                    volatility = VolatilityMetrics(abs(event.change.eth), abs(event.change.btc), event.volatility),
                    lastVolatilityUpdate = clock.nowMillis(),
                )
            val sized = withSizeFee(withVolatility)
            val volatilityMultiplier = 1 + (withVolatility.volatility.overall / 100) * VOLATILITY_MULTIPLIER
            val combined = sizeMultiplier(sized.poolSizeRatio) * 0.6 + volatilityMultiplier * 0.4
            pool = sized.copy(feeRate = clampFee(BASE_FEE_RATE * combined))
            log.debug("동적 수수료 업데이트: {}", pool.feeRate)
        }

    private fun requireInitialized(): LpPool {
        if (pool.users.isEmpty()) throw badRequest(NOT_INITIALIZED)
        return pool
    }

    private fun recalculated(pool: LpPool): LpPool = withSizeFee(withShares(pool))

    /** Share = user value / pool value, both in ETH at the pool price, to 3 decimals. */
    private fun withShares(pool: LpPool): LpPool {
        val price = pool.eth.toDouble() / pool.btc.toDouble()
        val total = poolValue(pool.eth, pool.btc)
        // Truncated (the original rounded) so that shares never sum above 1 and payouts never exceed the fee.
        return pool.copy(
            users =
                pool.users.map {
                    it.copy(
                        share =
                            ((it.eth.toDouble() + it.btc.toDouble() * price) / total)
                                .toBigDecimal()
                                .setScale(
                                    3,
                                    RoundingMode.DOWN,
                                ).toDouble(),
                    )
                },
        )
    }

    private fun withSizeFee(pool: LpPool): LpPool {
        val currentValue = poolValue(pool.eth, pool.btc)
        val sizeRatio = currentValue / pool.initialPoolValue
        return pool.copy(
            currentPoolValue = currentValue,
            poolSizeRatio = sizeRatio,
            feeRate = clampFee(BASE_FEE_RATE * sizeMultiplier(sizeRatio)),
        )
    }

    private fun sizeMultiplier(sizeRatio: Double) = max(0.1, 1 / sqrt(sizeRatio))

    private fun clampFee(rate: Double) = max(MIN_FEE_RATE, min(MAX_FEE_RATE, rate))

    /** ETH value of the pool at its own price, as in the original (equals 2 x eth). */
    private fun poolValue(
        eth: BigDecimal,
        btc: BigDecimal,
    ): Double = eth.toDouble() + btc.toDouble() * (eth.toDouble() / btc.toDouble())

    private fun newUser(
        id: Int,
        eth: BigDecimal,
        btc: BigDecimal,
    ) = LpUser(id, eth, btc, 0.0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

    private fun emptyPool() =
        LpPool(
            eth = BigDecimal.ZERO,
            btc = BigDecimal.ZERO,
            k = BigDecimal.ZERO,
            feeRate = BASE_FEE_RATE,
            userCount = 0,
            users = emptyList(),
            initialPoolValue = 0.0,
            currentPoolValue = 0.0,
            poolSizeRatio = 1.0,
            volatility = VolatilityMetrics(0.0, 0.0, 0.0),
            lastVolatilityUpdate = clock.nowMillis(),
        )

    private fun badRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, message)

    private fun scale3(value: BigDecimal) = value.setScale(3, RoundingMode.HALF_UP)

    private fun round(
        value: Double,
        decimals: Int,
    ) = value.toBigDecimal().setScale(decimals, RoundingMode.HALF_UP).toDouble()

    companion object {
        const val NOT_INITIALIZED = "풀이 초기화되지 않았습니다. 먼저 POST /lp/init을 호출해주세요."
        const val BASE_FEE_RATE = 0.003
        const val MIN_FEE_RATE = 0.0005
        const val MAX_FEE_RATE = 0.01
        const val INITIAL_USERS = 10
        const val MIN_USERS = 10
        const val MAX_USERS = 30
        val TARGET_ETH = BigDecimal("1000")
        val TARGET_BTC = BigDecimal("30000")
        private const val MAX_INITIAL_SHARE_OF_REMAINING = 0.8
        private const val MAX_USERS_PER_ADD = 5
        private const val MAX_USERS_PER_REMOVE = 3
        private const val VOLATILITY_MULTIPLIER = 2
        private const val FEE_DECIMALS = 6
        private val TOKENS_PER_FEE = BigDecimal.TEN
    }
}
