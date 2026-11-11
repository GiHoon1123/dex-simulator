package io.github.gihoon1123.dexsimulator.shared.events

import io.github.gihoon1123.dexsimulator.common.event.DomainEvent
import io.github.gihoon1123.dexsimulator.shared.pool.AddLiquidityResult
import io.github.gihoon1123.dexsimulator.shared.pool.MevOpportunity
import io.github.gihoon1123.dexsimulator.shared.pool.PoolState
import io.github.gihoon1123.dexsimulator.shared.pool.PoolStats
import io.github.gihoon1123.dexsimulator.shared.pool.RemoveLiquidityResult
import io.github.gihoon1123.dexsimulator.shared.pool.SwapResult
import java.math.BigDecimal
import java.time.Instant

/** Event names kept identical to the original `POOL_EVENT_TYPES`. */
object PoolEventTypes {
    const val POOL_CREATED = "pool.created"
    const val POOL_UPDATED = "pool.updated"
    const val SWAP_EXECUTED = "swap.executed"
    const val LIQUIDITY_ADDED = "liquidity.added"
    const val LIQUIDITY_REMOVED = "liquidity.removed"
    const val MEV_OPPORTUNITY_DETECTED = "mev.opportunity.detected"
    const val MEV_OPPORTUNITY_EXPIRED = "mev.opportunity.expired"
    const val POOL_STATS_UPDATED = "pool.stats.updated"
    const val ORACLE_STATUS_CHANGED = "oracle.status.changed"
    const val POOL_TRANSACTION_CREATED = "pool.transaction.created"
}

// `price.deviation.detected` is declared in the original but never published, so it is
// not ported.
sealed interface PoolEvent : DomainEvent

data class PoolCreationMetadata(
    val creator: String,
    val initialLiquidity: Double,
    val gasUsed: Long,
)

data class PoolCreatedEvent(
    override val eventId: String,
    val pool: PoolState,
    override val timestamp: Instant,
    val metadata: PoolCreationMetadata,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.POOL_CREATED
}

data class Change<T>(
    val from: T,
    val to: T,
)

data class PoolChanges(
    val reserveA: Change<BigDecimal>? = null,
    val reserveB: Change<BigDecimal>? = null,
    val totalVolume: Change<BigDecimal>? = null,
    val volume24h: Change<BigDecimal>? = null,
) {
    companion object {
        fun between(
            previous: PoolState,
            current: PoolState,
        ): PoolChanges {
            fun diff(
                from: BigDecimal,
                to: BigDecimal,
            ) = if (from.compareTo(to) != 0) Change(from, to) else null
            return PoolChanges(
                reserveA = diff(previous.reserveA, current.reserveA),
                reserveB = diff(previous.reserveB, current.reserveB),
                totalVolume = diff(previous.totalVolume, current.totalVolume),
                volume24h = diff(previous.volume24h, current.volume24h),
            )
        }
    }
}

enum class PoolUpdateReason {
    SWAP,
    LIQUIDITY_ADD,
    LIQUIDITY_REMOVE,
}

data class PoolUpdatedEvent(
    override val eventId: String,
    val poolAddress: String,
    val previousState: PoolState,
    val currentState: PoolState,
    val changes: PoolChanges,
    override val timestamp: Instant,
    val reason: PoolUpdateReason,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.POOL_UPDATED
}

data class SwapExecutedEvent(
    override val eventId: String,
    val swapResult: SwapResult,
    val poolState: PoolState,
    override val timestamp: Instant,
    /** Decided by the MEV bot, always false here as in the original. */
    val isMEVOpportunity: Boolean = false,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.SWAP_EXECUTED
}

data class LiquidityAddedEvent(
    override val eventId: String,
    val addLiquidityResult: AddLiquidityResult,
    val poolState: PoolState,
    override val timestamp: Instant,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.LIQUIDITY_ADDED
}

data class LiquidityRemovedEvent(
    override val eventId: String,
    val removeLiquidityResult: RemoveLiquidityResult,
    val poolState: PoolState,
    override val timestamp: Instant,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.LIQUIDITY_REMOVED
}

enum class MevPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
    ;

    companion object {
        fun of(expectedProfit: Double): MevPriority =
            when {
                expectedProfit > 5 -> URGENT
                expectedProfit > 2 -> HIGH
                expectedProfit > 1 -> MEDIUM
                else -> LOW
            }
    }
}

data class MevOpportunityDetectedEvent(
    override val eventId: String,
    val mevOpportunity: MevOpportunity,
    override val timestamp: Instant,
    val priority: MevPriority,
    val autoExecutable: Boolean,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.MEV_OPPORTUNITY_DETECTED
}

data class MevOpportunityExpiredEvent(
    override val eventId: String,
    val mevOpportunityId: String,
    val poolAddress: String,
    override val timestamp: Instant,
    /** Only `timeout` is used, as in the original. */
    val reason: String,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.MEV_OPPORTUNITY_EXPIRED
}

data class PoolStatsUpdatedEvent(
    override val eventId: String,
    val poolAddress: String,
    val poolStats: PoolStats,
    override val timestamp: Instant,
    val updateInterval: Int,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.POOL_STATS_UPDATED
}

data class OracleStatusChangedEvent(
    override val eventId: String,
    val previousStatus: String,
    val currentStatus: String,
    override val timestamp: Instant,
    val reason: String,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.ORACLE_STATUS_CHANGED
}

/** Uniswap V3 `swap()` call arguments carried by a pool transaction. */
data class SwapCallParams(
    val recipient: String,
    val zeroForOne: Boolean,
    val amountSpecified: String,
    val sqrtPriceLimitX96: String,
    val data: String,
)

/**
 * Transaction structure handed to the blockchain module. Only swaps create one in the
 * original, so the liquidity variants are not ported.
 */
data class PoolTransaction(
    val id: String,
    val from: String,
    val to: String,
    val value: String,
    val swapParams: SwapCallParams,
    val gasPrice: Long,
    val gasLimit: Long,
    val nonce: Long,
) {
    val type: String get() = "SWAP"
}

data class PoolTransactionCreatedEvent(
    override val eventId: String,
    val transaction: PoolTransaction,
    val poolAddress: String,
    override val timestamp: Instant,
) : PoolEvent {
    override val type: String get() = PoolEventTypes.POOL_TRANSACTION_CREATED
}
