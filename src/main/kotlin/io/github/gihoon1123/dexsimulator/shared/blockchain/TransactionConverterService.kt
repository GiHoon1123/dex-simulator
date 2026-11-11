package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.events.PoolTransactionCreatedEvent
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns swaps executed by the pool module into mempool transactions, ported from the
 * original `TransactionConverterService`.
 *
 * Fixed in the migration (docs/decisions.md #10): the original used the pool's random
 * nonce, so most swaps of a repeat sender were rejected, and stored the type as "SWAP"
 * instead of "swap", which made gas estimation fall back to the default.
 */
@Service
class TransactionConverterService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val transactionPool: TransactionPoolService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun subscribe() {
        events.subscribe<PoolTransactionCreatedEvent>(sim.scope) { handle(it) }
    }

    suspend fun handle(event: PoolTransactionCreatedEvent) {
        val source = event.transaction
        try {
            val params = source.swapParams
            val callData =
                SwapAbi.encode(
                    SwapCallArgs(params.recipient, params.zeroForOne, params.amountSpecified, params.sqrtPriceLimitX96, params.data),
                )
            transactionPool.submit(
                Transaction(
                    id = source.id,
                    type = TransactionType.SWAP,
                    from = source.from,
                    to = source.to,
                    value = source.value,
                    data = callData,
                    gasPrice = source.gasPrice,
                    gasLimit = source.gasLimit,
                    status = TransactionStatus.PENDING,
                    nonce = transactionPool.getCurrentNonce(source.from),
                    timestamp = clock.nowMillis(),
                    parsedData = SwapAbi.decode(callData),
                ),
            )
            log.info("트랜잭션 메모풀 제출 완료: {}", source.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A rejected transaction must not stop the subscription.
            log.error("트랜잭션 처리 실패: {} ({})", source.id, e.message)
        }
    }
}
