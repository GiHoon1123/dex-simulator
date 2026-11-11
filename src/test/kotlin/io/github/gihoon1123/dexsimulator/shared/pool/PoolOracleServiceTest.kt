package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.shared.events.MevOpportunityDetectedEvent
import io.github.gihoon1123.dexsimulator.shared.events.MevOpportunityExpiredEvent
import io.github.gihoon1123.dexsimulator.shared.events.OracleStatusChangedEvent
import io.github.gihoon1123.dexsimulator.shared.pool.PoolServiceTest.Companion.ETH_USDC
import io.github.gihoon1123.dexsimulator.support.EventRecorder
import io.github.gihoon1123.dexsimulator.support.MutableClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoolOracleServiceTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val pools = PoolService(sim, bus, clock).apply { initializeDefaultPools() }
    private val oracle = PoolOracleService(sim, bus, pools, clock)

    @AfterEach
    fun tearDown() = sim.close()

    private suspend fun startOracle() {
        oracle.start()
        // Existing pools are registered once the subscription is active.
        withTimeout(2_000) { while (oracle.getAllPoolPrices().size < 4) delay(5) }
    }

    private suspend fun swapEth(amount: String) {
        val params =
            SwapParams(ETH_USDC, "ETH", "USDC", BigDecimal(amount), BigDecimal.ZERO, "u", clock.instant().epochSecond + 60, 100, 200_000)
        assertTrue(pools.executeSwap(params).success)
    }

    private suspend fun awaitPrice(predicate: (Double) -> Boolean) =
        withTimeout(2_000) { while (!predicate(oracle.getPoolPrice(ETH_USDC)!!.price)) delay(5) }

    @Test
    fun `registers existing pools on start, which the original never did`() =
        runBlocking {
            startOracle()

            val price = oracle.getPoolPrice(ETH_USDC)
            assertNotNull(price)
            assertEquals(2000.0, price.price)
            assertEquals(1.0, price.confidence)
            assertEquals(4, oracle.getOracleStatus().totalPools)
            assertTrue(oracle.getOracleStatus().isActive)
        }

    @Test
    fun `pool updates refresh the oracle price`() =
        runBlocking {
            startOracle()

            swapEth("10")

            awaitPrice { it < 2000.0 }
            val price = oracle.getPoolPrice(ETH_USDC)!!
            assertEquals(1 / price.price, price.inversePrice, 1e-12)
        }

    @Test
    fun `twap weights each price by the seconds it lasted`() =
        runBlocking {
            startOracle()
            oracle.performPeriodicUpdate() // 2000 recorded at t0
            clock.advance(Duration.ofSeconds(30))
            swapEth("10")
            awaitPrice { it < 2000.0 }
            val moved = oracle.getPoolPrice(ETH_USDC)!!.price
            oracle.performPeriodicUpdate() // moved price at t0 + 30s
            clock.advance(Duration.ofSeconds(10))
            oracle.performPeriodicUpdate() // moved price at t0 + 40s

            val twap = oracle.calculateTwap(ETH_USDC)
            assertNotNull(twap)
            assertEquals(3, twap.sampleCount)
            assertEquals(40.0, twap.totalWeight)
            // Weights go to the later sample of each interval, as in the original.
            assertEquals(moved, twap.twapPrice, 1e-9)
            assertNull(oracle.calculateTwap(ETH_USDC, period = 5))
        }

    @Test
    fun `price deviation beyond the threshold becomes an opportunity that expires`() =
        runBlocking {
            startOracle()
            val recorder = EventRecorder(bus).start()
            swapEth("50") // ETH/USDC moves about 9% below the external 2000
            awaitPrice { it < 1900.0 }

            oracle.performPeriodicUpdate()

            val opportunity = oracle.getActiveOpportunities(MevOpportunityFilter(poolAddress = ETH_USDC)).single()
            assertEquals(MevStrategy.ARBITRAGE, opportunity.strategy)
            assertEquals(4, opportunity.riskLevel)
            assertTrue(opportunity.isExecutable)
            assertEquals(opportunity, oracle.getOpportunity(opportunity.opportunityId))
            assertTrue(oracle.getActiveOpportunities(MevOpportunityFilter(strategy = MevStrategy.SANDWICH)).isEmpty())
            // The corrected BTC/ETH external price (20) matches the pool, so no false opportunity.
            assertEquals(1, oracle.getActiveOpportunities().size)

            clock.advance(Duration.ofSeconds(61))
            oracle.checkExpiredOpportunities()

            assertTrue(oracle.getActiveOpportunities().isEmpty())
            recorder.awaitUntil { events -> events.any { it is MevOpportunityExpiredEvent } }
            assertEquals(1, recorder.ofType<MevOpportunityDetectedEvent>().size)
            recorder.stop()
        }

    @Test
    fun `deviation reports direction and threshold`() =
        runBlocking {
            startOracle()

            val deviation = oracle.calculatePriceDeviation(ETH_USDC, 1900.0)!!

            assertEquals(DeviationDirection.POOL_HIGHER, deviation.direction)
            assertEquals(100.0, deviation.absoluteDifference)
            assertTrue(deviation.isMEVOpportunity)
            assertNull(oracle.calculatePriceDeviation("0xnone", 1.0))
        }

    @Test
    fun `stop and start toggle the status and publish changes`() =
        runBlocking {
            startOracle()
            val recorder = EventRecorder(bus).start()

            oracle.stop()
            assertFalse(oracle.getOracleStatus().isActive)
            oracle.start()
            assertTrue(oracle.getOracleStatus().isActive)
            oracle.start() // already active: no second event

            recorder.awaitUntil { it.size >= 2 }
            assertEquals(
                listOf("inactive", "active"),
                recorder.ofType<OracleStatusChangedEvent>().map { it.currentStatus },
            )
            recorder.stop()
        }

    @Test
    fun `config updates are merged`() =
        runBlocking {
            oracle.updateConfig { it.copy(mevThreshold = 2.0) }

            val config = oracle.getConfig()
            assertEquals(2.0, config.mevThreshold)
            assertEquals(300, config.twapPeriod)
        }
}
