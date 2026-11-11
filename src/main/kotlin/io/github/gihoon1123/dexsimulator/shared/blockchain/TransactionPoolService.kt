package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.events.PendingPoolSnapshot
import io.github.gihoon1123.dexsimulator.shared.events.TransactionPendingEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Mempool, ported from the original `TransactionPoolService`.
 *
 * Ordering: higher gas price first, then lower nonce. Nonces are per sender and may skip
 * values but never go back, as in the original.
 */
@Service
class TransactionPoolService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val clock: Clock,
) {
    private val pending = LinkedHashMap<String, Transaction>()
    private val nonces = HashMap<String, Long>()

    /** Adds [transaction] as pending. Re-submitting a pending id replaces it without a nonce check. */
    suspend fun submit(transaction: Transaction): Transaction =
        sim.run {
            if (pending.size >= MAX_PENDING_TX && !pending.containsKey(transaction.id)) {
                throw ApiException(HttpStatus.BAD_REQUEST, "트랜잭션 풀이 가득 찼습니다. (최대: $MAX_PENDING_TX)")
            }
            if (!pending.containsKey(transaction.id)) acceptNonce(transaction)

            val submitted = transaction.copy(status = TransactionStatus.PENDING)
            pending[submitted.id] = submitted
            val now = clock.nowMillis()
            events.publish(
                TransactionPendingEvent(EventIds.next(now), submitted, PendingPoolSnapshot(pending.size, averageGasPrice()), now),
            )
            submitted
        }

    suspend fun getPendingTransactions(): List<Transaction> = sim.run { sortedPending() }

    /** Takes the best transactions that fit in [maxGasLimit] by gas limit and removes them from the pool. */
    suspend fun selectTransactionsForBlock(maxGasLimit: Long): List<Transaction> =
        sim.run {
            var totalGas = 0L
            // Stops at the first transaction that does not fit, as in the original.
            val selected =
                sortedPending()
                    .takeWhile { tx -> (totalGas + tx.gasLimit <= maxGasLimit).also { fits -> if (fits) totalGas += tx.gasLimit } }
                    .map { it.copy(status = TransactionStatus.SELECTED) }
            selected.forEach { pending.remove(it.id) }
            selected
        }

    suspend fun getTransactionById(id: String): Transaction? = sim.run { pending[id] }

    suspend fun removeTransaction(id: String) = sim.run { pending.remove(id) }

    suspend fun getPoolStatus(): TransactionPoolStatus =
        sim.run {
            val gasPrices = pending.values.map { it.gasPrice }
            TransactionPoolStatus(
                pendingCount = pending.size,
                totalTransactions = pending.size,
                averageGasPrice = averageGasPrice(),
                highestGasPrice = gasPrices.maxOrNull() ?: 0,
                lowestGasPrice = gasPrices.minOrNull() ?: 0,
            )
        }

    /** Clears pending transactions and returns how many were removed. Nonces are kept. */
    suspend fun clearPool(): Int =
        sim.run {
            val count = pending.size
            pending.clear()
            count
        }

    suspend fun isGasLimitReached(gasLimit: Long): Boolean = sim.run { pending.values.sumOf { it.gasLimit } >= gasLimit }

    suspend fun getCurrentNonce(address: String): Long = sim.run { nonces[address] ?: 0 }

    private fun acceptNonce(transaction: Transaction) {
        val current = nonces[transaction.from] ?: 0
        if (transaction.nonce < current) {
            throw ApiException(HttpStatus.BAD_REQUEST, "유효하지 않은 논스입니다. 현재: $current, 제출: ${transaction.nonce}")
        }
        nonces[transaction.from] = transaction.nonce + 1
    }

    private fun sortedPending(): List<Transaction> =
        pending.values.sortedWith(compareByDescending<Transaction> { it.gasPrice }.thenBy { it.nonce })

    private fun averageGasPrice(): Double = if (pending.isEmpty()) 0.0 else pending.values.map { it.gasPrice }.average()

    companion object {
        const val MAX_PENDING_TX = 100
    }
}
