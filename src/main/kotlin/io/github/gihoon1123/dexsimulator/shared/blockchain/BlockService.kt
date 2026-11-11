package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.events.BlockCreatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.BlockCreatingEvent
import io.github.gihoon1123.dexsimulator.shared.events.BlockExecutedEvent
import io.github.gihoon1123.dexsimulator.shared.events.BlockExecutingEvent
import io.github.gihoon1123.dexsimulator.shared.events.TransactionExecutedEvent
import io.github.gihoon1123.dexsimulator.shared.events.TransactionFailedEvent
import io.github.gihoon1123.dexsimulator.shared.events.TransactionSelectedEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Block production, ported from the original `BlockService`.
 *
 * Execution is simulated as in the original: a transaction only consumes an estimated
 * amount of gas and does not touch pools. A transaction that runs out of gas is
 * re-queued with a fresh nonce and a 20% higher gas limit.
 */
@Service
class BlockService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val transactionPool: TransactionPoolService,
    private val gasService: GasService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val blockchain = ArrayList<Block>()
    private var currentBlockNumber = 0L
    private var autoProduction: Job? = null
    private var lastBlockTime: Instant = Instant.EPOCH

    /** Creates the next block and executes it. */
    suspend fun produceBlock(): BlockResult = sim.run { executeBlock(createBlock()) }

    suspend fun getBlockchain(): List<Block> = sim.run { blockchain.toList() }

    suspend fun getBlockByNumber(blockNumber: Long): Block? = sim.run { blockchain.firstOrNull { it.blockNumber == blockNumber } }

    suspend fun getLatestBlock(): Block? = sim.run { blockchain.lastOrNull() }

    suspend fun getBlockchainStatus(): BlockchainStatus =
        sim.run {
            val totalTransactions = blockchain.sumOf { it.transactionCount }
            val blockTimes = blockchain.zipWithNext { a, b -> Duration.between(a.timestamp, b.timestamp).toMillis().toDouble() }
            BlockchainStatus(
                latestBlockNumber = currentBlockNumber,
                totalBlocks = blockchain.size,
                totalTransactions = totalTransactions,
                averageBlockTime = blockTimes.average().takeUnless { it.isNaN() } ?: 0.0,
                averageTransactionsPerBlock = if (blockchain.isEmpty()) 0.0 else totalTransactions.toDouble() / blockchain.size,
            )
        }

    /** Checks every second and produces a block after 12 seconds or when pending gas fills a block. */
    suspend fun startAutoProduction() =
        sim.run {
            if (autoProduction != null) return@run
            lastBlockTime = clock.nowMillis()
            autoProduction =
                sim.scope.launch {
                    while (true) {
                        delay(CHECK_INTERVAL.toMillis())
                        try {
                            checkAndProduceBlock()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("자동 블록 생성 실패", e)
                        }
                    }
                }
            log.info("자동 블록 생성 시작 (1초마다 체크, 12초 경과 또는 가스 한도 도달 시 블록 생성)")
        }

    suspend fun stopAutoProduction() =
        sim.run {
            autoProduction?.cancel()
            autoProduction = null
            log.info("자동 블록 생성 중지")
        }

    suspend fun isAutoProduction(): Boolean = sim.run { autoProduction != null }

    /** One round of auto production. Returns the produced block, if any. */
    suspend fun checkAndProduceBlock(): BlockResult? =
        sim.run {
            val now = clock.nowMillis()
            val elapsed = Duration.between(lastBlockTime, now)
            val timeUp = elapsed >= BLOCK_INTERVAL
            if (!timeUp && !transactionPool.isGasLimitReached(BLOCK_GAS_LIMIT)) return@run null

            val result = executeBlock(createBlock())
            lastBlockTime = now
            log.info(
                "블록 생성 완료 ({}): 블록 #{}, 트랜잭션 {}개, 가스 사용량 {}/{}",
                if (timeUp) "시간 경과" else "가스 한도 도달",
                result.block.blockNumber,
                result.block.transactionCount,
                result.block.gasUsed,
                BLOCK_GAS_LIMIT,
            )
            result
        }

    private suspend fun createBlock(): Block {
        val blockNumber = ++currentBlockNumber
        val now = clock.nowMillis()
        events.publish(BlockCreatingEvent(EventIds.next(now), blockNumber, transactionPool.getPoolStatus().pendingCount, now))

        val transactions = transactionPool.selectTransactionsForBlock(BLOCK_GAS_LIMIT)
        transactions.forEachIndexed { index, tx ->
            events.publish(TransactionSelectedEvent(EventIds.next(now), tx, blockNumber, index, now))
        }
        val previousHash = blockchain.lastOrNull()?.hash ?: GENESIS_PREVIOUS_HASH
        val block =
            Block(
                blockNumber = blockNumber,
                hash = blockHash(blockNumber, previousHash, now, transactions),
                previousHash = previousHash,
                timestamp = now,
                transactions = transactions,
                transactionCount = transactions.size,
                gasUsed = 0,
                gasLimit = BLOCK_GAS_LIMIT,
                status = BlockStatus.PENDING,
                producer = PRODUCER,
            )
        blockchain += block
        events.publish(BlockCreatedEvent(EventIds.next(now), block, now))
        return block
    }

    private suspend fun executeBlock(created: Block): BlockResult {
        val started = System.nanoTime()
        val executing = created.copy(status = BlockStatus.EXECUTING)
        replace(executing)
        events.publish(BlockExecutingEvent(EventIds.next(clock.nowMillis()), executing, clock.nowMillis()))

        val processed = executing.transactions.map { execute(it, executing.blockNumber) }
        val executed = processed.filter { it.status == TransactionStatus.COMPLETED }
        val failed = processed.filter { it.status == TransactionStatus.FAILED }
        processed.forEach { tx ->
            val now = clock.nowMillis()
            if (tx.status == TransactionStatus.COMPLETED) {
                events.publish(TransactionExecutedEvent(EventIds.next(now), tx, executing.blockNumber, tx.gasUsed ?: 0, true, now))
            } else {
                requeue(tx)
                events.publish(TransactionFailedEvent(EventIds.next(now), tx, tx.error ?: "Unknown error", now))
            }
        }

        val totalGasUsed = executed.sumOf { it.gasUsed ?: 0 }
        val completed =
            executing.copy(
                transactions = processed,
                gasUsed = totalGasUsed,
                status = BlockStatus.COMPLETED,
            )
        replace(completed)
        val executionTime = (System.nanoTime() - started) / NANOS_PER_MILLI
        val now = clock.nowMillis()
        events.publish(BlockExecutedEvent(EventIds.next(now), completed, executed.size, failed.size, totalGasUsed, executionTime, now))

        return BlockResult(
            block = completed,
            executedTransactions = executed,
            failedTransactions = failed,
            totalGasUsed = totalGasUsed,
            totalFees = executed.sumOf { gasService.calculateTotalCost(it.gasUsed ?: 0, it.gasPrice) },
            executionTime = executionTime,
        )
    }

    private fun execute(
        tx: Transaction,
        blockNumber: Long,
    ): Transaction {
        val gasUsed = gasService.estimateGas(tx.type)
        val included = tx.copy(blockNumber = blockNumber, blockTimestamp = clock.nowMillis(), gasUsed = gasUsed)
        if (gasUsed > tx.gasLimit) {
            return included.copy(status = TransactionStatus.FAILED, error = OUT_OF_GAS)
        }
        return included.copy(
            status = TransactionStatus.COMPLETED,
            result =
                TransactionResult(
                    success = true,
                    gasUsed = gasUsed,
                    effectiveGasPrice = tx.gasPrice,
                    output = TransactionOutput("트랜잭션이 블록에 포함되어 실행되었습니다"),
                ),
        )
    }

    /**
     * Puts a failed transaction back into the pool. The original cleared the error before
     * checking it, so the gas limit was never raised and an out-of-gas transaction could
     * fail again and again.
     */
    private suspend fun requeue(failed: Transaction) {
        val gasLimit = if (failed.error == OUT_OF_GAS) (failed.gasLimit * GAS_LIMIT_RETRY_FACTOR).toLong() else failed.gasLimit
        val retry =
            failed.copy(
                status = TransactionStatus.PENDING,
                nonce = transactionPool.getCurrentNonce(failed.from),
                gasLimit = gasLimit,
                blockNumber = null,
                blockTimestamp = null,
                gasUsed = null,
                error = null,
                result = null,
            )
        try {
            transactionPool.submit(retry)
            log.info("실패한 트랜잭션 {}을 풀에 다시 추가했습니다 (nonce: {}, gasLimit: {})", retry.id, retry.nonce, retry.gasLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("실패한 트랜잭션 {} 풀 재추가 실패: {}", retry.id, e.message)
        }
    }

    private fun replace(block: Block) {
        val index = blockchain.indexOfLast { it.blockNumber == block.blockNumber }
        blockchain[index] = block
    }

    /** SHA-256 over the block contents, linked to the previous hash. The original hex-encoded a short string. */
    private fun blockHash(
        blockNumber: Long,
        previousHash: String,
        timestamp: Instant,
        transactions: List<Transaction>,
    ): String {
        val content = "$blockNumber|$previousHash|${timestamp.toEpochMilli()}|${transactions.joinToString(",") { it.id }}"
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        return "0x" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val BLOCK_GAS_LIMIT = 30_000_000L
        const val PRODUCER = "simulator"
        const val OUT_OF_GAS = "가스 한도 초과"
        const val GENESIS_PREVIOUS_HASH = "0x0000000000000000000000000000000000000000"
        val BLOCK_INTERVAL: Duration = Duration.ofSeconds(12)
        val CHECK_INTERVAL: Duration = Duration.ofSeconds(1)
        private const val GAS_LIMIT_RETRY_FACTOR = 1.2
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
