package io.github.gihoon1123.dexsimulator.mev

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.gihoon1123.dexsimulator.common.event.DomainEvent
import java.time.Instant

// MEV bot models with the original field names. Profits and gas costs are ETH estimates of
// the simulation, kept as Double like other display values (decisions #3).

enum class MevStrategyType {
    FRONT_RUN,
    BACK_RUN,
    SANDWICH,
}

enum class MevOpportunityStatus {
    DETECTED,
    ANALYZING,
    EXECUTING,
    COMPLETED,
    FAILED,
    EXPIRED,
}

enum class MevBotStatus {
    STOPPED,
    RUNNING,
    PAUSED,
    ERROR,
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MevOpportunity(
    val id: String,
    val targetTransactionId: String,
    val targetPoolAddress: String,
    val strategy: MevStrategyType,
    val estimatedProfit: Double,
    /** 1..10 */
    val riskLevel: Double,
    val gasCost: Double,
    val netProfit: Double,
    /** 0..1 */
    val confidence: Double,
    val status: MevOpportunityStatus,
    val detectedAt: Instant,
    val expiresAt: Instant,
    val errorMessage: String? = null,
)

data class MevBotConfig(
    val minProfit: Double = 0.1,
    /** Highest accepted risk score (1..10). The original documented it as ETH. */
    val maxRisk: Double = 5.0,
    val gasPriceMultiplier: Double = 1.2,
    val maxOpportunities: Long = 10,
    val opportunityTimeout: Long = 30_000,
    val minConfidence: Double = 0.6,
    val enabledStrategies: List<MevStrategyType> = MevStrategyType.entries,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MevBotState(
    val status: MevBotStatus,
    val config: MevBotConfig,
    val activeOpportunities: List<MevOpportunity>,
    val totalOpportunities: Int,
    val successfulAttacks: Int,
    val totalProfit: Double,
    val averageProfit: Double,
    val successRate: Double,
    val lastActivity: Instant,
    val errorMessage: String? = null,
)

data class StrategyBreakdown(
    /** Successful executions. */
    val count: Int,
    val profit: Double,
    /** Successful / attempted executions of this strategy, in percent. */
    val successRate: Double,
)

data class TimeRange(
    val start: Instant,
    val end: Instant,
)

data class MevStats(
    val totalOpportunities: Int,
    val successfulAttacks: Int,
    val failedAttacks: Int,
    val totalProfit: Double,
    val totalGasSpent: Double,
    val netProfit: Double,
    val averageProfit: Double,
    /** Percent. */
    val successRate: Double,
    val strategyBreakdown: Map<MevStrategyType, StrategyBreakdown>,
    val timeRange: TimeRange,
)

data class DetectionCriteria(
    /** ETH value of the victim swap. */
    val minTransactionValue: Double,
    /** gwei */
    val minGasPrice: Long,
    val minSlippage: Double,
    val maxPoolImpact: Double,
    val minProfitThreshold: Double,
)

/** A transaction the bot would send. Gas price in gwei, like the rest of the simulator. */
data class MevTransaction(
    val to: String,
    val value: String,
    val data: String,
    val gasPrice: Long,
    val gasLimit: Long,
    val nonce: Long,
)

data class SubmittedTransactions(
    val submitted: List<MevTransaction>,
    val confirmed: List<String>,
    val failed: List<String>,
)

data class StrategyMetadata(
    val poolAddress: String,
    val targetTransactionId: String,
    val strategyParams: Map<String, Double>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StrategyExecutionResult(
    val strategy: MevStrategyType,
    val success: Boolean,
    val profit: Double,
    val gasUsed: Double,
    val netProfit: Double,
    val executionTime: Long,
    val transactions: SubmittedTransactions,
    val metadata: StrategyMetadata,
    val errorMessage: String? = null,
)

object MevEventTypes {
    const val OPPORTUNITY_DETECTED = "mev.opportunity.detected"
    const val OPPORTUNITY_QUEUED = "mev.opportunity.queued"
    const val OPPORTUNITY_EXECUTED = "mev.opportunity.executed"
    const val BOT_STARTED = "mev.bot.started"
    const val BOT_STOPPED = "mev.bot.stopped"
}

/** Bot-side opportunity events. Same names as the original; distinct from the pool oracle's event of the same name. */
data class MevBotEvent(
    override val eventId: String,
    override val type: String,
    val opportunity: MevOpportunity? = null,
    val result: StrategyExecutionResult? = null,
    override val timestamp: Instant,
) : DomainEvent
