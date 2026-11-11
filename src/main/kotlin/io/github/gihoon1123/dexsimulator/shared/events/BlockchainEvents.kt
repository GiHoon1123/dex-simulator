package io.github.gihoon1123.dexsimulator.shared.events

import io.github.gihoon1123.dexsimulator.common.event.DomainEvent
import io.github.gihoon1123.dexsimulator.shared.blockchain.Block
import io.github.gihoon1123.dexsimulator.shared.blockchain.Transaction
import java.time.Instant

/** Event names kept identical to the strings emitted by the original blockchain module. */
object BlockchainEventTypes {
    const val TRANSACTION_PENDING = "transaction.pending"
    const val TRANSACTION_SELECTED = "transaction.selected"
    const val TRANSACTION_EXECUTED = "transaction.executed"
    const val TRANSACTION_FAILED = "transaction.failed"
    const val BLOCK_CREATING = "block.creating"
    const val BLOCK_CREATED = "block.created"
    const val BLOCK_EXECUTING = "block.executing"
    const val BLOCK_EXECUTED = "block.executed"
}

// The original events had no id or type field; they are added for the common DomainEvent shape.
sealed interface BlockchainEvent : DomainEvent

data class PendingPoolSnapshot(
    val pendingCount: Int,
    val averageGasPrice: Double,
)

data class TransactionPendingEvent(
    override val eventId: String,
    val transaction: Transaction,
    val poolStatus: PendingPoolSnapshot,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.TRANSACTION_PENDING
}

data class TransactionSelectedEvent(
    override val eventId: String,
    val transaction: Transaction,
    val blockNumber: Long,
    val position: Int,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.TRANSACTION_SELECTED
}

data class TransactionExecutedEvent(
    override val eventId: String,
    val transaction: Transaction,
    val blockNumber: Long,
    val gasUsed: Long,
    val success: Boolean,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.TRANSACTION_EXECUTED
}

data class TransactionFailedEvent(
    override val eventId: String,
    val transaction: Transaction,
    val error: String,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.TRANSACTION_FAILED
}

data class BlockCreatingEvent(
    override val eventId: String,
    val blockNumber: Long,
    val transactionCount: Int,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.BLOCK_CREATING
}

data class BlockCreatedEvent(
    override val eventId: String,
    val block: Block,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.BLOCK_CREATED
}

data class BlockExecutingEvent(
    override val eventId: String,
    val block: Block,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.BLOCK_EXECUTING
}

data class BlockExecutedEvent(
    override val eventId: String,
    val block: Block,
    val executedCount: Int,
    val failedCount: Int,
    val totalGasUsed: Long,
    val executionTime: Long,
    override val timestamp: Instant,
) : BlockchainEvent {
    override val type: String get() = BlockchainEventTypes.BLOCK_EXECUTED
}
