package io.github.gihoon1123.dexsimulator.shared.blockchain

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

private const val TYPE_PATTERN = "swap|addLiquidity|removeLiquidity|transfer|mevFrontrun|mevBackrun|mevSandwich"

data class SubmitTransactionRequest(
    @Pattern(regexp = TYPE_PATTERN) val type: String,
    @NotBlank val from: String,
    @NotBlank val to: String,
    val value: String? = null,
    @Pattern(regexp = "^0x[0-9a-fA-F]*$") val data: String,
    @Min(50) val gasPrice: Long? = null,
    @Min(21000) val gasLimit: Long? = null,
)

data class GetBlocksQuery(
    @Min(1) val limit: Int? = null,
    @Min(0) val offset: Int? = null,
)

data class GetTransactionsQuery(
    @Min(1) val limit: Int? = null,
)

data class EstimateGasQuery(
    @NotBlank @Pattern(regexp = TYPE_PATTERN) val type: String,
)

data class BlockPage(
    val blocks: List<Block>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
)

data class PendingTransactions(
    val transactions: List<Transaction>,
    val total: Int,
)

data class ClearPoolResponse(
    val message: String,
    val clearedCount: Int,
)

data class ProduceBlockResponse(
    val message: String,
    val block: Block,
    val result: BlockResult,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToggleResponse(
    val message: String,
    val isActive: Boolean,
    val interval: Any? = null,
)

data class AutoProductionStatus(
    val isActive: Boolean,
    val interval: Long,
    val message: String,
)

data class SubmitPosition(
    val pendingCount: Int,
    val position: Int,
    val estimatedBlocksUntilExecution: Int,
)

data class SubmitTransactionResponse(
    val message: String,
    val transaction: Transaction,
    val poolStatus: SubmitPosition,
)

data class SuggestedGasPrices(
    val low: Long,
    val medium: Long,
    val high: Long,
    val urgent: Long,
)

data class GasPriceResponse(
    val baseGasPrice: Long,
    val currentNetworkPrice: Long,
    val suggested: SuggestedGasPrices,
)

data class GasEstimateResponse(
    val estimatedGas: Long,
    val gasPrice: Long,
    val estimatedCost: Long,
)

data class GeneratedTransactionResponse(
    val message: String,
    val transaction: Transaction,
)

data class GasStats(
    val currentPrice: Long,
    val averagePrice: Double,
)

data class AutoProductionInfo(
    val isActive: Boolean,
    val interval: Long,
)

data class BlockchainStatsResponse(
    val blockchain: BlockchainStatus,
    val txPool: TransactionPoolStatus,
    val gas: GasStats,
    val autoProduction: AutoProductionInfo,
    val txGenerator: TransactionGeneratorStats,
)

data class NotFoundMessage(
    val message: String,
)
