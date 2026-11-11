package io.github.gihoon1123.dexsimulator.mev

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.shared.blockchain.BlockService
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionGeneratorService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class StartMevBotRequest(
    @DecimalMin("0") val minProfit: Double? = null,
    @DecimalMin("0") val maxRisk: Double? = null,
    @DecimalMin("1") val gasPriceMultiplier: Double? = null,
    @Min(1) val maxOpportunities: Long? = null,
    @Min(1000) val opportunityTimeout: Long? = null,
    @DecimalMin("0") @DecimalMax("1") val minConfidence: Double? = null,
    val enabledStrategies: List<MevStrategyType>? = null,
) {
    fun applyTo(config: MevBotConfig) =
        config.copy(
            minProfit = minProfit ?: config.minProfit,
            maxRisk = maxRisk ?: config.maxRisk,
            gasPriceMultiplier = gasPriceMultiplier ?: config.gasPriceMultiplier,
            maxOpportunities = maxOpportunities ?: config.maxOpportunities,
            opportunityTimeout = opportunityTimeout ?: config.opportunityTimeout,
            minConfidence = minConfidence ?: config.minConfidence,
            enabledStrategies = enabledStrategies ?: config.enabledStrategies,
        )
}

data class GetMevOpportunitiesQuery(
    val status: MevOpportunityStatus? = null,
    val strategy: MevStrategyType? = null,
    @DecimalMin("0") val minProfit: Double? = null,
    @DecimalMin("0") val maxRisk: Double? = null,
    @Min(1) val limit: Int? = null,
    @Min(0) val offset: Int? = null,
)

data class StartBotResponse(
    val success: Boolean,
    val message: String,
    val botState: MevBotState,
)

data class StopBotResponse(
    val success: Boolean,
    val message: String,
)

data class BotStatusResponse(
    val botState: MevBotState,
    val queueStatus: QueueStatus,
)

data class OpportunityPage(
    val opportunities: List<MevOpportunity>,
    val total: Int,
    val active: Int,
)

data class MevStatsResponse(
    val totalOpportunities: Int,
    val successfulAttacks: Int,
    val failedAttacks: Int,
    val totalProfit: Double,
    val totalGasSpent: Double,
    val netProfit: Double,
    val averageProfit: Double,
    val successRate: Double,
    val strategyBreakdown: Map<MevStrategyType, StrategyBreakdown>,
    val timeRange: TimeRange,
    val botStatus: MevBotStatus,
    val lastActivity: Instant,
)

/**
 * Port of the original `BlockchainPrerequisitesGuard`: MEV APIs need block production and
 * the transaction generator running, otherwise there is no mempool traffic to attack.
 */
@Component
class BlockchainPrerequisites(
    private val blockService: BlockService,
    private val generator: TransactionGeneratorService,
) {
    suspend fun check() {
        if (!blockService.isAutoProduction()) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "블록체인 시뮬레이션을 위해서는 블록 자동생성이 활성화되어야 합니다 (POST /blockchain/auto-production/start 를 먼저 호출하세요)",
            )
        }
        if (!generator.isActive()) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "블록체인 시뮬레이션을 위해서는 트랜잭션 자동생성이 활성화되어야 합니다 (POST /blockchain/tx-generator/start 를 먼저 호출하세요)",
            )
        }
    }
}

/** Same routes and bodies as the original `controllers/mev.controller.ts` (the unused root controller is not ported). */
@Tag(name = "MEV")
@RestController
@RequestMapping("/mev")
class MevController(
    private val bot: MevBotService,
    private val detector: MevDetectorService,
    private val prerequisites: BlockchainPrerequisites,
) {
    @Operation(summary = "MEV 봇 시작")
    @PostMapping("/bot/start")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun startBot(
        @Valid @RequestBody(required = false) request: StartMevBotRequest?,
    ): StartBotResponse {
        prerequisites.check()
        bot.startBot { config -> request?.applyTo(config) ?: config }
        return StartBotResponse(true, "MEV 봇이 시작되었습니다", bot.getBotState())
    }

    @Operation(summary = "MEV 봇 중지")
    @PostMapping("/bot/stop")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun stopBot(): StopBotResponse {
        prerequisites.check()
        bot.stopBot()
        return StopBotResponse(true, "MEV 봇이 중지되었습니다")
    }

    @Operation(summary = "MEV 봇 상태 조회")
    @GetMapping("/bot/status")
    suspend fun getBotStatus(): BotStatusResponse {
        prerequisites.check()
        return BotStatusResponse(bot.getBotState(), bot.getQueueStatus())
    }

    @Operation(summary = "MEV 기회 목록 조회")
    @GetMapping("/opportunities")
    suspend fun getOpportunities(
        @Valid @ModelAttribute query: GetMevOpportunitiesQuery,
    ): OpportunityPage {
        prerequisites.check()
        val filtered =
            detector.getOpportunities().filter { o ->
                (query.status == null || o.status == query.status) &&
                    (query.strategy == null || o.strategy == query.strategy) &&
                    (query.minProfit == null || o.estimatedProfit >= query.minProfit) &&
                    (query.maxRisk == null || o.riskLevel <= query.maxRisk)
            }
        val offset = query.offset ?: 0
        val limit = query.limit ?: DEFAULT_LIMIT
        return OpportunityPage(filtered.drop(offset).take(limit), filtered.size, bot.getActiveOpportunities().size)
    }

    @Operation(summary = "MEV 통계 조회")
    @GetMapping("/stats")
    suspend fun getStats(): MevStatsResponse {
        prerequisites.check()
        val stats = bot.getStats()
        val state = bot.getBotState()
        return MevStatsResponse(
            totalOpportunities = stats.totalOpportunities,
            successfulAttacks = stats.successfulAttacks,
            failedAttacks = stats.failedAttacks,
            totalProfit = stats.totalProfit,
            totalGasSpent = stats.totalGasSpent,
            netProfit = stats.netProfit,
            averageProfit = stats.averageProfit,
            successRate = stats.successRate,
            strategyBreakdown = stats.strategyBreakdown,
            timeRange = stats.timeRange,
            botStatus = state.status,
            lastActivity = state.lastActivity,
        )
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
    }
}
