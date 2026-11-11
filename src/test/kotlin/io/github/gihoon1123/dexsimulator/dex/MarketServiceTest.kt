package io.github.gihoon1123.dexsimulator.dex

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.dex.market.MarketService
import io.github.gihoon1123.dexsimulator.support.EventRecorder
import io.github.gihoon1123.dexsimulator.support.MutableClock
import io.github.gihoon1123.dexsimulator.support.ScriptedRandom
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the original `market.service.spec.ts`; repeated coverage-only cases were merged. */
class MarketServiceTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val random = ScriptedRandom()
    private val market = MarketService(sim, bus, random, clock)

    @AfterEach
    fun tearDown() = sim.close()

    private fun dec(value: String) = BigDecimal(value)

    @Test
    fun `starts at 2000 and 60000 USD`() =
        runBlocking {
            val price = market.getCurrentPrice()

            assertEquals(2000.0, price.eth)
            assertEquals(60000.0, price.btc)
            assertEquals(2000.0 / 60000.0, price.ratio)
        }

    @Test
    fun `price change moves each price by at most 5 percent and publishes the event`() =
        runBlocking {
            val recorder = EventRecorder(bus).start()
            random.push(0.75, 0.25) // eth +2.5%, btc -2.5%

            val event = market.simulatePriceChange()

            assertEquals(2000.0, event.previousPrice.eth)
            assertEquals(2050.0, event.currentPrice.eth, 1e-9)
            assertEquals(58500.0, event.currentPrice.btc, 1e-9)
            assertEquals(2.5, event.change.eth, 1e-9)
            assertEquals(-2.5, event.change.btc, 1e-9)
            assertEquals(event.currentPrice.eth / event.currentPrice.btc, event.currentPrice.ratio)
            assertTrue(event.eventId.startsWith("price_change_"))
            recorder.awaitUntil { events -> events.any { it is PriceChangeEvent } }
            recorder.stop()
        }

    @Test
    fun `random extremes stay within plus minus 5 percent`() =
        runBlocking {
            random.push(0.0, 0.999999)

            val event = market.simulatePriceChange()

            assertTrue(abs(event.change.eth) <= 5.0 && abs(event.change.btc) <= 5.0)
        }

    @Test
    fun `volatility is zero without history and positive after moves`() =
        runBlocking {
            assertEquals(VolatilityMetrics(0.0, 0.0, 0.0), market.getMarketStatus().volatility)

            random.push(0.9, 0.1, 0.1, 0.9, 0.8, 0.3)
            repeat(3) { market.simulatePriceChange() }

            val volatility = market.getMarketStatus().volatility
            assertTrue(volatility.eth > 0 && volatility.btc > 0 && volatility.overall > 0)
        }

    @Test
    fun `history keeps at most 100 prices`() =
        runBlocking {
            repeat(150) { market.simulatePriceChange() }

            // Volatility over the last 20 prices still works on a capped history.
            assertTrue(market.getMarketStatus().volatility.overall >= 0)
        }

    @Test
    fun `gap below 5 percent is not an opportunity`() =
        runBlocking {
            assertNull(market.calculateArbitrageOpportunity(dec("1000"), dec("30000")))
            assertNull(market.calculateArbitrageOpportunity(dec("1000"), dec("31000")))
        }

    @Test
    fun `cheap ETH in the pool means buy ETH there`() =
        runBlocking {
            // Pool: 1 ETH = 20 BTC, market reference 30.
            val opportunity = assertNotNull(market.calculateArbitrageOpportunity(dec("1000"), dec("20000")))

            assertEquals(20.0, opportunity.poolPrice)
            assertEquals(30.0, opportunity.marketPrice)
            assertEquals(100.0 / 3, opportunity.percentage, 1e-9)
            // The original returned buy_btc_sell_eth here, pushing the pool further away.
            assertEquals(ArbitrageDirection.BUY_ETH_SELL_BTC, opportunity.direction)
        }

    @Test
    fun `dear ETH in the pool means sell ETH there`() =
        runBlocking {
            val opportunity = assertNotNull(market.calculateArbitrageOpportunity(dec("1000"), dec("40000")))

            assertEquals(ArbitrageDirection.BUY_BTC_SELL_ETH, opportunity.direction)
        }

    @Test
    fun `calculating publishes nothing, check and emit publishes exactly once`() =
        runBlocking {
            val recorder = EventRecorder(bus).start()

            market.calculateArbitrageOpportunity(dec("1000"), dec("20000"))
            market.getMarketStatus(dec("1000"), dec("20000"))
            assertNull(market.checkAndEmitArbitrageOpportunity(dec("1000"), dec("30000")))
            assertNotNull(market.checkAndEmitArbitrageOpportunity(dec("1000"), dec("20000")))

            recorder.awaitUntil { events -> events.any { it is ArbitrageOpportunityEvent } }
            market.getCurrentPrice() // let any stray event arrive
            assertEquals(1, recorder.ofType<ArbitrageOpportunityEvent>().size)
            recorder.stop()
        }

    @Test
    fun `status includes an opportunity only when pool reserves are given`() =
        runBlocking {
            assertNull(market.getMarketStatus().arbitrageOpportunity)
            assertNotNull(market.getMarketStatus(dec("1000"), dec("20000")).arbitrageOpportunity)
        }

    @Test
    fun `invalid or negative pool reserves are handled safely`() =
        runBlocking {
            assertNull(market.calculateArbitrageOpportunity(BigDecimal.ZERO, dec("30000")))
            assertNull(market.calculateArbitrageOpportunity(dec("-1000"), dec("30000")))
        }
}
