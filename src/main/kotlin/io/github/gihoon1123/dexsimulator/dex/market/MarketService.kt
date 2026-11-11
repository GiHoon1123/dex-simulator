package io.github.gihoon1123.dexsimulator.dex.market

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.dex.ArbitrageDirection
import io.github.gihoon1123.dexsimulator.dex.ArbitrageOpportunity
import io.github.gihoon1123.dexsimulator.dex.ArbitrageOpportunityEvent
import io.github.gihoon1123.dexsimulator.dex.MarketPrice
import io.github.gihoon1123.dexsimulator.dex.MarketStatus
import io.github.gihoon1123.dexsimulator.dex.PriceChange
import io.github.gihoon1123.dexsimulator.dex.PriceChangeEvent
import io.github.gihoon1123.dexsimulator.dex.VolatilityMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Random walk of ETH and BTC USD prices, ported from the original `MarketService`.
 *
 * Fixed in the migration (decisions #11): the arbitrage direction was inverted, and
 * computing an opportunity also published it, so one opportunity was published twice by
 * `checkAndEmitArbitrageOpportunity` and a status read could trigger trades.
 */
@Service
class MarketService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val random: Random,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var currentPrice = MarketPrice(INITIAL_ETH, INITIAL_BTC, INITIAL_ETH / INITIAL_BTC, clock.nowMillis())
    private val priceHistory = ArrayDeque<MarketPrice>().apply { add(currentPrice) }

    suspend fun getCurrentPrice(): MarketPrice = sim.run { currentPrice }

    /** Moves each price by a random -5%..+5% and publishes `market.price.changed`. */
    suspend fun simulatePriceChange(): PriceChangeEvent =
        sim.run {
            val previous = currentPrice
            val ethChange = (random.nextDouble() - 0.5) * MAX_STEP
            val btcChange = (random.nextDouble() - 0.5) * MAX_STEP
            val eth = previous.eth * (1 + ethChange)
            val btc = previous.btc * (1 + btcChange)
            val now = clock.nowMillis()
            currentPrice = MarketPrice(eth, btc, eth / btc, now)
            priceHistory.addLast(currentPrice)
            if (priceHistory.size > MAX_HISTORY) priceHistory.removeFirst()

            val event =
                PriceChangeEvent(
                    eventId = "price_change_${now.toEpochMilli()}",
                    timestamp = now,
                    previousPrice = previous,
                    currentPrice = currentPrice,
                    change = PriceChange(ethChange * 100, btcChange * 100),
                    volatility = overallVolatility(),
                )
            events.publish(event)
            event
        }

    /** Pure check, publishes nothing. Null when the gap is below 5%. */
    suspend fun calculateArbitrageOpportunity(
        poolEth: BigDecimal,
        poolBtc: BigDecimal,
    ): ArbitrageOpportunity? = sim.run { opportunityOf(poolEth, poolBtc) }

    /** Publishes `arbitrage.opportunity` once when there is one. */
    suspend fun checkAndEmitArbitrageOpportunity(
        poolEth: BigDecimal,
        poolBtc: BigDecimal,
    ): ArbitrageOpportunity? =
        sim.run {
            val opportunity = opportunityOf(poolEth, poolBtc) ?: return@run null
            log.info("아비트라지 기회 발견: {}% 차이", String.format(Locale.ROOT, "%.2f", opportunity.percentage))
            events.publish(ArbitrageOpportunityEvent(EventIds.next(opportunity.timestamp), opportunity, opportunity.timestamp))
            opportunity
        }

    suspend fun getMarketStatus(
        poolEth: BigDecimal? = null,
        poolBtc: BigDecimal? = null,
    ): MarketStatus =
        sim.run {
            MarketStatus(
                currentPrice = currentPrice,
                volatility =
                    VolatilityMetrics(
                        eth = volatility { it.eth },
                        btc = volatility { it.btc },
                        overall = overallVolatility(),
                    ),
                arbitrageOpportunity = if (poolEth != null && poolBtc != null) opportunityOf(poolEth, poolBtc) else null,
                lastUpdate = clock.nowMillis(),
            )
        }

    private fun opportunityOf(
        poolEth: BigDecimal,
        poolBtc: BigDecimal,
    ): ArbitrageOpportunity? {
        if (poolEth.signum() <= 0 || poolBtc.signum() <= 0) return null
        val poolPrice = poolBtc.toDouble() / poolEth.toDouble()
        val marketPrice = currentPrice.btc / currentPrice.eth
        val difference = abs(poolPrice - marketPrice)
        val percentage = difference / marketPrice * 100
        if (percentage < ARBITRAGE_THRESHOLD_PERCENT) return null

        val now = clock.nowMillis()
        return ArbitrageOpportunity(
            opportunityId = "arbitrage_${now.toEpochMilli()}",
            timestamp = now,
            poolPrice = poolPrice,
            marketPrice = marketPrice,
            difference = difference,
            percentage = percentage,
            // ETH cheaper in the pool -> buy it there. The original had this inverted.
            direction = if (poolPrice < marketPrice) ArbitrageDirection.BUY_ETH_SELL_BTC else ArbitrageDirection.BUY_BTC_SELL_ETH,
        )
    }

    /** Volatility of the average ETH/BTC return. */
    private fun overallVolatility(): Double = stdDevPercent(returns { it.eth }.zip(returns { it.btc }) { e, b -> (e + b) / 2 })

    private fun volatility(price: (MarketPrice) -> Double): Double = stdDevPercent(returns(price))

    private fun returns(price: (MarketPrice) -> Double): List<Double> =
        priceHistory.takeLast(VOLATILITY_WINDOW).zipWithNext { a, b -> (price(b) - price(a)) / price(a) }

    /** Population standard deviation in percent; 0 with fewer than two prices. */
    private fun stdDevPercent(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / values.size) * 100
    }

    companion object {
        const val INITIAL_ETH = 2000.0
        const val INITIAL_BTC = 60000.0
        const val MAX_HISTORY = 100
        const val VOLATILITY_WINDOW = 20
        const val ARBITRAGE_THRESHOLD_PERCENT = 5.0
        private const val MAX_STEP = 0.1
    }
}
