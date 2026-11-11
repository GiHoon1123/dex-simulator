package io.github.gihoon1123.dexsimulator.mev

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.shared.blockchain.SwapAbi
import io.github.gihoon1123.dexsimulator.shared.blockchain.SwapCallArgs
import io.github.gihoon1123.dexsimulator.shared.blockchain.Transaction
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionPoolService
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionStatus
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionType
import io.github.gihoon1123.dexsimulator.shared.pool.PoolService
import io.github.gihoon1123.dexsimulator.support.MutableClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MevServicesTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val mempool = TransactionPoolService(sim, bus, clock)
    private val pools = PoolService(sim, bus, clock).apply { initializeDefaultPools() }
    private val detector = MevDetectorService(sim, bus, mempool, pools, clock)
    private val strategy = MevStrategyService(Random(7))
    private val bot = MevBotService(sim, bus, detector, strategy, clock)

    @AfterEach
    fun tearDown() = sim.close()

    /** Victim swap on ETH/USDC: [amount] in minimal units of the input token. */
    private suspend fun victim(
        id: String,
        amount: String,
        zeroForOne: Boolean = true,
        gasPrice: Long = 150,
        type: TransactionType = TransactionType.SWAP,
    ) {
        val data = SwapAbi.encode(SwapCallArgs(id, zeroForOne, amount, "0", "0x"))
        mempool.submit(
            Transaction(
                id = id,
                type = type,
                from = id,
                to = ETH_USDC,
                value = "0",
                data = data,
                gasPrice = gasPrice,
                gasLimit = 300_000,
                status = TransactionStatus.PENDING,
                nonce = 0,
                timestamp = clock.instant(),
                parsedData = SwapAbi.decode(data),
            ),
        )
    }

    private suspend fun startDetection(config: MevBotConfig = MevBotConfig(minProfit = 0.01)) {
        detector.startDetection(config)
        detector.stopDetection() // keep the criteria, drive scans by hand
    }

    @Test
    fun `large ETH swap becomes a sandwich opportunity with risk from gwei gas price`() =
        runBlocking {
            startDetection()
            victim("whale", "10000000000000000000", gasPrice = 300) // 10 ETH

            val opportunity = detector.scanPendingTransactions().single()

            assertEquals(MevStrategyType.SANDWICH, opportunity.strategy)
            assertEquals(0.3, opportunity.estimatedProfit, 1e-9)
            assertEquals(0.295, opportunity.netProfit, 1e-9)
            // 300 gwei -> 3. The original divided by 1e9 again and always got 0.
            assertEquals(3.0, opportunity.riskLevel)
            assertEquals(0.7, opportunity.confidence)
            assertEquals(clock.instant().plusSeconds(30), opportunity.expiresAt)
        }

    @Test
    fun `amounts use the input token decimals and ETH prices`() =
        runBlocking {
            startDetection()
            // 20000 USDC (6 decimals) = 10 ETH at the mock price; the original read it as 2e-8 ETH.
            victim("usdc-whale", "20000000000", zeroForOne = false)

            val opportunity = detector.scanPendingTransactions().single()

            assertEquals(0.3, opportunity.estimatedProfit, 1e-9)
        }

    @Test
    fun `each pending transaction is analyzed once`() =
        runBlocking {
            startDetection()
            victim("whale", "10000000000000000000")

            detector.scanPendingTransactions()
            detector.scanPendingTransactions()
            detector.scanPendingTransactions()

            // The original created a new opportunity for the same swap on every scan.
            assertEquals(1, detector.getOpportunities().size)
        }

    @Test
    fun `small, cheap or non swap transactions are skipped`() =
        runBlocking {
            startDetection()
            victim("small", "10000000000000000") // 0.01 ETH
            victim("cheap", "10000000000000000000", gasPrice = 40)
            victim("transfer", "10000000000000000000", type = TransactionType.TRANSFER)

            assertTrue(detector.scanPendingTransactions().isEmpty())
        }

    @Test
    fun `expired opportunities are dropped`() =
        runBlocking {
            startDetection()
            victim("whale", "10000000000000000000")
            detector.scanPendingTransactions()

            clock.advance(Duration.ofSeconds(31))
            detector.scanPendingTransactions()

            assertTrue(detector.getOpportunities().isEmpty())
        }

    @Test
    fun `strategy profit is the estimate realized at 95 percent minus gas`() {
        val opportunity =
            MevOpportunity(
                "o",
                "tx",
                ETH_USDC,
                MevStrategyType.FRONT_RUN,
                1.0,
                2.0,
                0.003,
                0.997,
                0.7,
                MevOpportunityStatus.DETECTED,
                clock.instant(),
                clock.instant(),
            )

        val result = strategy.executeStrategy(opportunity)

        assertTrue(result.success)
        assertEquals(0.95 * (1 - 0.2 * 0.05), result.profit, 1e-12)
        assertEquals(result.profit - 0.003, result.netProfit, 1e-12)
        assertEquals(2, result.transactions.submitted.size)
        val leg = result.transactions.submitted.first()
        assertEquals(250, leg.gasPrice)
        assertEquals("1000000000000000000", assertNotNull(SwapAbi.decode(leg.data)).params.amountSpecified)

        val weak = strategy.executeStrategy(opportunity.copy(confidence = 0.2))
        assertEquals("전략 실행 조건을 만족하지 않습니다", weak.errorMessage)
    }

    @Test
    fun `bot queues accepted opportunities, executes one per round and keeps per strategy success rate`() =
        runBlocking {
            bot.startBot { it.copy(minProfit = 0.01, maxRisk = 8.0, minConfidence = 0.5) }
            victim("a", "10000000000000000000")
            victim("b", "20000000000000000000")
            detector.scanPendingTransactions()

            bot.processOnce()
            assertEquals(1, bot.getQueueStatus().queueLength)
            bot.processOnce()

            val stats = bot.getStats()
            assertEquals(2, stats.totalOpportunities)
            assertEquals(2, stats.successfulAttacks)
            // 2 of 2 sandwich attempts succeeded. The original reported each strategy's share instead.
            assertEquals(100.0, stats.strategyBreakdown.getValue(MevStrategyType.SANDWICH).successRate)
            assertEquals(2, stats.strategyBreakdown.getValue(MevStrategyType.SANDWICH).count)
            assertTrue(detector.getOpportunities().isEmpty())
            assertEquals(MevBotStatus.RUNNING, bot.getBotState().status)

            bot.stopBot()
            assertEquals(MevBotStatus.STOPPED, bot.getBotState().status)
            assertTrue(!detector.isDetectionActive())
        }

    @Test
    fun `bot skips opportunities outside its config`() =
        runBlocking {
            bot.startBot { it.copy(minProfit = 0.01, maxRisk = 2.0) }
            victim("risky", "10000000000000000000", gasPrice = 300) // risk 3
            detector.scanPendingTransactions()

            bot.processOnce()

            assertEquals(0, bot.getBotState().totalOpportunities)
            assertEquals(1, detector.getOpportunities().size)
            bot.stopBot()
        }

    companion object {
        const val ETH_USDC = "0x8ad599c3A0ff1De082011EFDDc58f1908eb6e6D8"
    }
}
