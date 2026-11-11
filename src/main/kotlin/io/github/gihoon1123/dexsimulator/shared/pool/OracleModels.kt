package io.github.gihoon1123.dexsimulator.shared.pool

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

data class PoolPrice(
    val poolAddress: String,
    val pair: String,
    val tokenA: String,
    val tokenB: String,
    /** Token B per token A. */
    val price: Double,
    val inversePrice: Double,
    val timestamp: Instant,
    /** 0..1 */
    val confidence: Double,
)

data class TwapInfo(
    val poolAddress: String,
    val twapPrice: Double,
    val period: Int,
    val sampleCount: Int,
    val startTime: Instant,
    val endTime: Instant,
    val totalWeight: Double,
)

enum class DeviationDirection(
    @get:JsonValue val wire: String,
) {
    POOL_HIGHER("pool_higher"),
    EXTERNAL_HIGHER("external_higher"),
    EQUAL("equal"),
}

data class PriceDeviation(
    val poolAddress: String,
    val poolPrice: Double,
    val externalPrice: Double,
    val absoluteDifference: Double,
    /** Percent. */
    val relativeDifference: Double,
    val direction: DeviationDirection,
    val detectedAt: Instant,
    val isMEVOpportunity: Boolean,
)

enum class MevStrategy(
    @get:JsonValue val wire: String,
) {
    FRONT_RUN("front_run"),
    BACK_RUN("back_run"),
    SANDWICH("sandwich"),
    ARBITRAGE("arbitrage"),
    ;

    companion object {
        fun of(wire: String): MevStrategy = entries.first { it.wire == wire }
    }
}

data class MevOpportunity(
    val opportunityId: String,
    val poolAddress: String,
    val pair: String,
    val strategy: MevStrategy,
    /** Percent. */
    val expectedProfit: Double,
    val expectedProfitUSD: Double,
    val gasCost: Double,
    val netProfit: Double,
    val detectedAt: Instant,
    val expiresAt: Instant,
    val isExecutable: Boolean,
    /** 1..5 */
    val riskLevel: Int,
)

data class OracleConfig(
    val twapPeriod: Int = 300,
    val updateInterval: Int = 30,
    val mevThreshold: Double = 0.5,
    val confidenceThreshold: Double = 0.8,
    val maxDeviation: Double = 10.0,
    val autoExecute: Boolean = false,
)

data class PriceHistoryItem(
    val price: Double,
    val timestamp: Instant,
    val volume: Double,
    val weight: Double,
)

enum class OracleSystemStatus(
    @get:JsonValue val wire: String,
) {
    HEALTHY("healthy"),
    WARNING("warning"),
    ERROR("error"),
}

data class OracleStatus(
    val isActive: Boolean,
    val lastUpdate: Instant,
    val totalPools: Int,
    val monitoredPools: Int,
    val activeOpportunities: Int,
    val averageConfidence: Double,
    val systemStatus: OracleSystemStatus,
)

data class MevOpportunityFilter(
    val poolAddress: String? = null,
    val strategy: MevStrategy? = null,
    val minProfit: Double? = null,
    val maxRisk: Int? = null,
    val executableOnly: Boolean = false,
)
