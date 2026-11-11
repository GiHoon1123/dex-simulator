package io.github.gihoon1123.dexsimulator.shared.blockchain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

enum class TransactionType(
    @get:JsonValue val wire: String,
) {
    SWAP("swap"),
    ADD_LIQUIDITY("addLiquidity"),
    REMOVE_LIQUIDITY("removeLiquidity"),
    TRANSFER("transfer"),
    MEV_FRONTRUN("mevFrontrun"),
    MEV_BACKRUN("mevBackrun"),
    MEV_SANDWICH("mevSandwich"),
    ;

    companion object {
        fun of(wire: String): TransactionType =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("지원하지 않는 트랜잭션 타입: $wire")
    }
}

enum class TransactionStatus(
    @get:JsonValue val wire: String,
) {
    PENDING("pending"),
    SELECTED("selected"),
    EXECUTING("executing"),
    COMPLETED("completed"),
    FAILED("failed"),
    DROPPED("dropped"),
}

/** Decoded arguments of `swap(address,bool,int256,uint160,bytes)`. */
data class SwapCallArgs(
    val recipient: String,
    val zeroForOne: Boolean,
    val amountSpecified: String,
    val sqrtPriceLimitX96: String,
    val data: String,
)

data class ParsedTransactionData(
    val function: String,
    val params: SwapCallArgs,
)

data class TransactionOutput(
    val message: String,
)

data class TransactionResult(
    val success: Boolean,
    val gasUsed: Long,
    val effectiveGasPrice: Long,
    val output: TransactionOutput,
)

/** Field names follow the original `Transaction` interface. Gas price is in gwei. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Transaction(
    val id: String,
    val type: TransactionType,
    val from: String,
    val to: String,
    val value: String,
    val data: String,
    val gasPrice: Long,
    val gasLimit: Long,
    val gasUsed: Long? = null,
    val status: TransactionStatus,
    val nonce: Long,
    val timestamp: Instant,
    val blockNumber: Long? = null,
    val blockTimestamp: Instant? = null,
    val result: TransactionResult? = null,
    val error: String? = null,
    val parsedData: ParsedTransactionData? = null,
)

data class TransactionPoolStatus(
    val pendingCount: Int,
    val totalTransactions: Int,
    val averageGasPrice: Double,
    val highestGasPrice: Long,
    val lowestGasPrice: Long,
)

enum class BlockStatus(
    @get:JsonValue val wire: String,
) {
    PENDING("pending"),
    EXECUTING("executing"),
    COMPLETED("completed"),
    FAILED("failed"),
}

data class Block(
    val blockNumber: Long,
    val hash: String,
    val previousHash: String,
    val timestamp: Instant,
    val transactions: List<Transaction>,
    val transactionCount: Int,
    val gasUsed: Long,
    val gasLimit: Long,
    val status: BlockStatus,
    val producer: String,
)

data class BlockResult(
    val block: Block,
    val executedTransactions: List<Transaction>,
    val failedTransactions: List<Transaction>,
    val totalGasUsed: Long,
    /** gas x gwei, as in the original. */
    val totalFees: Long,
    /** Milliseconds. */
    val executionTime: Long,
)

data class BlockchainStatus(
    val latestBlockNumber: Long,
    val totalBlocks: Int,
    val totalTransactions: Int,
    /** Milliseconds. */
    val averageBlockTime: Double,
    val averageTransactionsPerBlock: Double,
)

data class TransactionGeneratorStats(
    val isGenerating: Boolean,
    val totalGenerated: Long,
    val totalUsers: Long,
)
