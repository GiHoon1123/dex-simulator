package io.github.gihoon1123.dexsimulator.dex.trader

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.dex.ArbitrageCheckResult
import io.github.gihoon1123.dexsimulator.dex.ArbitrageDirection
import io.github.gihoon1123.dexsimulator.dex.ArbitrageOpportunity
import io.github.gihoon1123.dexsimulator.dex.ArbitrageOpportunityEvent
import io.github.gihoon1123.dexsimulator.dex.Asset
import io.github.gihoon1123.dexsimulator.dex.PoolSnapshot
import io.github.gihoon1123.dexsimulator.dex.PriceInfo
import io.github.gihoon1123.dexsimulator.dex.Trade
import io.github.gihoon1123.dexsimulator.dex.TradeExecutedEvent
import io.github.gihoon1123.dexsimulator.dex.TradeResult
import io.github.gihoon1123.dexsimulator.dex.lp.LpService
import io.github.gihoon1123.dexsimulator.dex.market.MarketService
import io.github.gihoon1123.dexsimulator.shared.pool.AmmMath
import io.github.gihoon1123.dexsimulator.shared.pool.Tokens
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * ETH/BTC trades against the LP pool, ported from the original `TraderService`.
 *
 * A trade reads the pool, prices it with `out = reserveOut - k / (reserveIn + inAfterFee)`,
 * applies it to the pool and publishes `trade.executed`, all in one simulation step.
 */
@Service
class TraderService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val lpService: LpService,
    private val marketService: MarketService,
    private val random: Random,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Shared by random and arbitrage trades, as in the original (trade_1, arbitrage_2, ...). */
    private var tradeCounter = 0L

    @PostConstruct
    fun subscribe() {
        events.subscribe<ArbitrageOpportunityEvent>(sim.scope) { handleArbitrageOpportunity(it.opportunity) }
    }

    /** Random direction, 1~6% of the input reserve. */
    suspend fun executeRandomTrade(): TradeResult =
        sim.run {
            val from = if (random.nextDouble() > 0.5) Asset.ETH else Asset.BTC
            executeTrade(from, random.nextDouble() * MAX_RANDOM_RATIO + MIN_TRADE_RATIO)
        }

    suspend fun executeTrade(
        from: Asset,
        tradeRatio: Double,
    ): TradeResult = sim.run { trade("trade", from, tradeRatio) }

    suspend fun handleArbitrageOpportunity(opportunity: ArbitrageOpportunity): TradeResult =
        sim.run {
            log.info(
                "아비트라지 기회 감지: {}% 차이, 방향: {}",
                String.format(Locale.ROOT, "%.2f", opportunity.percentage),
                opportunity.direction.wire,
            )
            arbitrage(opportunity)
        }

    /** Compares the pool with the market and trades when the gap is at least 5%. */
    suspend fun checkAndExecuteArbitrage(): ArbitrageCheckResult =
        sim.run {
            val pool = lpService.getPool()
            val opportunity = marketService.calculateArbitrageOpportunity(pool.eth, pool.btc)
            if (opportunity == null) {
                val market = marketService.getCurrentPrice()
                val poolPrice = pool.btc.toDouble() / pool.eth.toDouble()
                val marketPrice = market.btc / market.eth
                val percentage = abs(poolPrice - marketPrice) / marketPrice * 100
                return@run ArbitrageCheckResult(
                    "아비트라지 기회가 없습니다. 현재 차이: ${format2(percentage)}% (최소 5% 필요)",
                )
            }
            val manual = opportunity.copy(opportunityId = "manual_arbitrage_${clock.millis()}")
            ArbitrageCheckResult("아비트라지 거래 실행 완료! 차이: ${format2(manual.percentage)}%", manual, arbitrage(manual))
        }

    private suspend fun arbitrage(opportunity: ArbitrageOpportunity): TradeResult {
        // At most 3% of the reserve or a tenth of the gap, at least 1%.
        val ratio = max(MIN_TRADE_RATIO, min(MAX_ARBITRAGE_RATIO, opportunity.percentage / 1000))
        val from = if (opportunity.direction == ArbitrageDirection.BUY_ETH_SELL_BTC) Asset.BTC else Asset.ETH
        val result = trade("arbitrage", from, ratio)
        log.info(
            "아비트라지 거래 완료: {} {} → {} {}",
            from.symbol,
            result.trade.amountIn.toPlainString(),
            from.other.symbol,
            result.trade.amountOut.toPlainString(),
        )
        return result
    }

    private suspend fun trade(
        idPrefix: String,
        from: Asset,
        tradeRatio: Double,
    ): TradeResult {
        val to = from.other
        val tradeId = "${idPrefix}_${++tradeCounter}"
        val pool = lpService.getPool()
        val before = PoolSnapshot(pool.eth, pool.btc, pool.k)
        val reserveIn = if (from == Asset.ETH) before.eth else before.btc
        val reserveOut = if (from == Asset.ETH) before.btc else before.eth
        val decimalsIn = Tokens.decimals(from.symbol)
        val decimalsOut = Tokens.decimals(to.symbol)

        // The ratio is a random Double; 6 decimals keep binary noise (0.060000000000000005) out of amounts.
        val ratio = BigDecimal.valueOf(tradeRatio).setScale(RATIO_DECIMALS, RoundingMode.HALF_UP)
        val amountIn = (reserveIn * ratio).setScale(decimalsIn, RoundingMode.DOWN)
        val fee = lpService.calculateFee(amountIn, decimalsIn)
        val inAfterFee = amountIn - fee
        val amountOut = (reserveOut - before.k.divide(reserveIn + inAfterFee, AmmMath.CALC)).setScale(decimalsOut, RoundingMode.DOWN)

        val expectedPrice = reserveOut.toDouble() / reserveIn.toDouble()
        val actualPrice = amountOut.toDouble() / inAfterFee.toDouble()
        val slippage = round(abs((actualPrice - expectedPrice) / expectedPrice) * 100, 4)
        val priceImpact = round(inAfterFee.toDouble() / reserveIn.toDouble() * 100, 4)
        // The fee is paid out to the LPs, so only the amount after fee enters the reserve. k is kept.
        val after =
            if (from == Asset.ETH) {
                PoolSnapshot(before.eth + inAfterFee, before.btc - amountOut, before.k)
            } else {
                PoolSnapshot(before.eth - amountOut, before.btc + inAfterFee, before.k)
            }

        val now = clock.nowMillis()
        val event =
            TradeExecutedEvent(EventIds.next(now), tradeId, from, to, amountIn, amountOut, fee, slippage, priceImpact, before, after, now)
        lpService.applyTrade(event)
        events.publish(event)

        return TradeResult(
            trade = Trade(tradeId, from, to, amountIn, amountOut, fee, slippage, priceImpact, now),
            poolBefore = before,
            poolAfter = after,
            priceInfo =
                PriceInfo(
                    expectedRate = round(expectedPrice, 6),
                    actualRate = round(amountOut.toDouble() / amountIn.toDouble(), 6),
                    slippage = slippage,
                ),
        )
    }

    private fun round(
        value: Double,
        decimals: Int,
    ) = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

    private fun format2(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    companion object {
        private const val MIN_TRADE_RATIO = 0.01
        private const val MAX_RANDOM_RATIO = 0.05
        private const val MAX_ARBITRAGE_RATIO = 0.03
        private const val RATIO_DECIMALS = 6
    }
}
