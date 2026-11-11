package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.shared.events.BlockExecutedEvent
import io.github.gihoon1123.dexsimulator.shared.events.TransactionFailedEvent
import io.github.gihoon1123.dexsimulator.shared.pool.PoolService
import io.github.gihoon1123.dexsimulator.shared.pool.SwapParams
import io.github.gihoon1123.dexsimulator.support.EventRecorder
import io.github.gihoon1123.dexsimulator.support.MutableClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockchainServicesTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val random = Random(42)
    private val gas = GasService(random)
    private val mempool = TransactionPoolService(sim, bus, clock)
    private val blocks = BlockService(sim, bus, mempool, gas, clock)

    @AfterEach
    fun tearDown() = sim.close()

    private fun tx(
        id: String,
        from: String = "alice",
        nonce: Long = 0,
        gasPrice: Long = 100,
        gasLimit: Long = 1_000_000,
    ) = Transaction(
        id = id,
        type = TransactionType.SWAP,
        from = from,
        to = POOL,
        value = "0",
        data = "0x",
        gasPrice = gasPrice,
        gasLimit = gasLimit,
        status = TransactionStatus.PENDING,
        nonce = nonce,
        timestamp = clock.instant(),
    )

    @Test
    fun `mempool orders by gas price then nonce`() =
        runBlocking {
            mempool.submit(tx("a", from = "alice", nonce = 0, gasPrice = 100))
            mempool.submit(tx("b", from = "bob", nonce = 0, gasPrice = 300))
            mempool.submit(tx("c", from = "alice", nonce = 1, gasPrice = 100))

            assertEquals(listOf("b", "a", "c"), mempool.getPendingTransactions().map { it.id })
            val status = mempool.getPoolStatus()
            assertEquals(3, status.pendingCount)
            assertEquals(300, status.highestGasPrice)
            assertEquals(500.0 / 3, status.averageGasPrice)
        }

    @Test
    fun `nonce may skip ahead but never go back`() =
        runBlocking {
            mempool.submit(tx("a", nonce = 5))
            assertEquals(6, mempool.getCurrentNonce("alice"))

            val error = assertThrows<ApiException> { mempool.submit(tx("b", nonce = 3)) }
            assertEquals("유효하지 않은 논스입니다. 현재: 6, 제출: 3", error.message)
        }

    @Test
    fun `full mempool rejects new transactions`() =
        runBlocking {
            repeat(TransactionPoolService.MAX_PENDING_TX) { mempool.submit(tx("t$it", from = "u$it")) }

            assertThrows<ApiException> { mempool.submit(tx("over", from = "late")) }
            Unit
        }

    @Test
    fun `block selection stops at the first transaction that does not fit`() =
        runBlocking {
            mempool.submit(tx("big", from = "a", gasPrice = 300, gasLimit = 20_000_000))
            mempool.submit(tx("huge", from = "b", gasPrice = 200, gasLimit = 20_000_000))
            mempool.submit(tx("small", from = "c", gasPrice = 100, gasLimit = 21_000))

            val selected = mempool.selectTransactionsForBlock(BlockService.BLOCK_GAS_LIMIT)

            assertEquals(listOf("big"), selected.map { it.id })
            assertEquals(TransactionStatus.SELECTED, selected.single().status)
            assertEquals(2, mempool.getPoolStatus().pendingCount)
        }

    @Test
    fun `produced block executes transactions and links hashes`() =
        runBlocking {
            mempool.submit(tx("ok", from = "a"))
            val first = blocks.produceBlock()
            clock.advance(Duration.ofSeconds(12))
            val second = blocks.produceBlock()

            assertEquals(1, first.block.blockNumber)
            assertEquals(BlockStatus.COMPLETED, first.block.status)
            assertEquals(BlockService.GENESIS_PREVIOUS_HASH, first.block.previousHash)
            assertEquals(first.block.hash, second.block.previousHash)
            assertEquals(66, first.block.hash.length)
            val executed = first.executedTransactions.single()
            assertEquals(TransactionStatus.COMPLETED, executed.status)
            assertEquals(1L, executed.blockNumber)
            assertEquals(executed.gasUsed, first.block.gasUsed)
            assertEquals(executed.gasUsed!! * 100, first.totalFees)
            assertEquals(12_000.0, blocks.getBlockchainStatus().averageBlockTime)
            assertEquals(first.block, blocks.getBlockByNumber(1))
        }

    @Test
    fun `out of gas transaction is requeued with a fresh nonce and a higher gas limit`() =
        runBlocking {
            val recorder = EventRecorder(bus).start()
            mempool.submit(tx("starved", from = "a", nonce = 0, gasLimit = 21_000))

            val result = blocks.produceBlock()

            val failed = result.failedTransactions.single()
            assertEquals(BlockService.OUT_OF_GAS, failed.error)
            assertEquals(0, result.block.gasUsed)
            val retried = assertNotNull(mempool.getTransactionById("starved"))
            assertEquals(TransactionStatus.PENDING, retried.status)
            assertEquals(1, retried.nonce)
            assertEquals(25_200, retried.gasLimit)
            assertNull(retried.error)
            recorder.awaitUntil { events -> events.any { it is BlockExecutedEvent } }
            assertEquals(1, recorder.ofType<TransactionFailedEvent>().size)
            recorder.stop()
        }

    @Test
    fun `auto production waits for the block interval`() =
        runBlocking {
            blocks.startAutoProduction()
            assertTrue(blocks.isAutoProduction())

            assertNull(blocks.checkAndProduceBlock())
            clock.advance(BlockService.BLOCK_INTERVAL)
            assertNotNull(blocks.checkAndProduceBlock())

            blocks.stopAutoProduction()
            assertTrue(!blocks.isAutoProduction())
        }

    @Test
    fun `swaps executed on a pool reach the mempool as abi encoded transactions`() =
        runBlocking {
            val pools = PoolService(sim, bus, clock).apply { initializeDefaultPools() }
            TransactionConverterService(sim, bus, mempool, clock).subscribe()
            val swap =
                SwapParams(
                    POOL,
                    "ETH",
                    "USDC",
                    BigDecimal("1.5"),
                    BigDecimal.ZERO,
                    "user-1",
                    clock.instant().epochSecond + 60,
                    100,
                    200_000,
                )

            // Two swaps from the same sender: the original's random nonces rejected most repeats.
            pools.executeSwap(swap)
            pools.executeSwap(swap)

            withTimeout(2_000) { while (mempool.getPoolStatus().pendingCount < 2) delay(5) }
            val pending = mempool.getPendingTransactions().sortedBy { it.nonce }
            assertEquals(listOf(0L, 1L), pending.map { it.nonce })
            val first = pending.first()
            assertEquals(TransactionType.SWAP, first.type)
            val params = assertNotNull(first.parsedData).params
            assertEquals(Addresses.of("user-1"), params.recipient)
            assertTrue(params.zeroForOne)
            assertEquals("1500000000000000000", params.amountSpecified)
        }

    @Test
    fun `generator submits decodable transactions with sequential nonces`() =
        runBlocking {
            val generator = TransactionGeneratorService(sim, mempool, gas, random, clock)

            val generated = (1..20).map { generator.generateRandomTransaction() }

            generated.forEach { tx ->
                val parsed = assertNotNull(tx.parsedData)
                assertTrue(BigDecimal(parsed.params.amountSpecified).signum() > 0)
                assertTrue(tx.gasPrice in 80..400)
            }
            generated.groupBy { it.from }.values.forEach { txs -> assertEquals(txs.indices.map { it.toLong() }, txs.map { it.nonce }) }
            assertEquals(20, generator.getStats().totalGenerated)
        }

    companion object {
        const val POOL = "0x8ad599c3A0ff1De082011EFDDc58f1908eb6e6D8"
    }
}
