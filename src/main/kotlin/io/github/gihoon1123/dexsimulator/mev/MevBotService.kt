package io.github.gihoon1123.dexsimulator.mev

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * MEV bot, ported from the original `MevBotService`: every second it drops expired
 * opportunities, queues new ones that pass its config, and executes one from the queue.
 *
 * Fixed in the migration (decisions #13): per-strategy success rate was each strategy's
 * share of all opportunities, not its own success rate. `pauseBot`/`resumeBot` were not
 * reachable from the API and resume never restarted processing, so they are not ported.
 */
@Service
class MevBotService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val detector: MevDetectorService,
    private val strategyService: MevStrategyService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val started = clock.nowMillis()
    private var status = MevBotStatus.STOPPED
    private var config = MevBotConfig()
    private val active = LinkedHashMap<String, MevOpportunity>()
    private val queue = ArrayDeque<MevOpportunity>()
    private var processing: Job? = null
    private var lastActivity = started

    private var totalOpportunities = 0
    private var successfulAttacks = 0
    private var botProfit = 0.0
    private var executedCount = 0
    private var failedAttacks = 0
    private var totalProfit = 0.0
    private var totalGasSpent = 0.0
    private var netProfit = 0.0
    private var statsEnd = started
    private val attemptsByStrategy = MevStrategyType.entries.associateWith { 0 }.toMutableMap()
    private val successesByStrategy = MevStrategyType.entries.associateWith { 0 }.toMutableMap()
    private val profitByStrategy = MevStrategyType.entries.associateWith { 0.0 }.toMutableMap()

    /** Merges [update] into the config and starts detection and processing. No-op when running. */
    suspend fun startBot(update: (MevBotConfig) -> MevBotConfig = { it }) =
        sim.run {
            if (status == MevBotStatus.RUNNING) return@run
            config = update(config)
            status = MevBotStatus.RUNNING
            lastActivity = clock.nowMillis()
            detector.startDetection(config)
            processing =
                sim.scope.launch {
                    while (true) {
                        try {
                            processOnce()
                            delay(PROCESS_INTERVAL.toMillis())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("기회 처리 중 오류 발생", e)
                            delay(ERROR_BACKOFF.toMillis())
                        }
                    }
                }
            publish(MevEventTypes.BOT_STARTED)
            log.info("MEV 봇이 시작되었습니다")
        }

    suspend fun stopBot() =
        sim.run {
            if (status == MevBotStatus.STOPPED) return@run
            status = MevBotStatus.STOPPED
            lastActivity = clock.nowMillis()
            detector.stopDetection()
            processing?.cancel()
            processing = null
            active.keys.forEach { detector.removeOpportunity(it) }
            active.clear()
            queue.clear()
            publish(MevEventTypes.BOT_STOPPED)
            log.info("MEV 봇이 중지되었습니다")
        }

    /** One processing round. Public so that tests can drive the bot without timers. */
    suspend fun processOnce() =
        sim.run {
            val now = clock.nowMillis()
            active.values.filter { it.expiresAt.isBefore(now) }.forEach {
                active.remove(it.id)
                detector.removeOpportunity(it.id)
            }
            detector.getOpportunities().filter { it.id !in active && accepts(it) }.forEach { enqueue(it) }
            queue.removeFirstOrNull()?.let { execute(it) }
        }

    suspend fun getBotState(): MevBotState =
        sim.run {
            MevBotState(
                status = status,
                config = config,
                activeOpportunities = active.values.toList(),
                totalOpportunities = totalOpportunities,
                successfulAttacks = successfulAttacks,
                totalProfit = botProfit,
                averageProfit = if (totalOpportunities > 0) botProfit / totalOpportunities else 0.0,
                successRate = if (totalOpportunities > 0) successfulAttacks * 100.0 / totalOpportunities else 0.0,
                lastActivity = lastActivity,
            )
        }

    suspend fun getStats(): MevStats =
        sim.run {
            MevStats(
                totalOpportunities = executedCount,
                successfulAttacks = executedCount - failedAttacks,
                failedAttacks = failedAttacks,
                totalProfit = totalProfit,
                totalGasSpent = totalGasSpent,
                netProfit = netProfit,
                averageProfit = if (executedCount > 0) totalProfit / executedCount else 0.0,
                successRate = if (executedCount > 0) (executedCount - failedAttacks) * 100.0 / executedCount else 0.0,
                strategyBreakdown =
                    MevStrategyType.entries.associateWith { type ->
                        val attempts = attemptsByStrategy.getValue(type)
                        val successes = successesByStrategy.getValue(type)
                        StrategyBreakdown(
                            successes,
                            profitByStrategy.getValue(type),
                            if (attempts >
                                0
                            ) {
                                successes * 100.0 / attempts
                            } else {
                                0.0
                            },
                        )
                    },
                timeRange = TimeRange(started, statsEnd),
            )
        }

    suspend fun getActiveOpportunities(): List<MevOpportunity> = sim.run { active.values.toList() }

    suspend fun getQueueStatus(): QueueStatus = sim.run { QueueStatus(queue.size, processing != null) }

    suspend fun updateConfig(update: (MevBotConfig) -> MevBotConfig) = sim.run { config = update(config) }

    private fun accepts(opportunity: MevOpportunity) =
        opportunity.estimatedProfit >= config.minProfit &&
            opportunity.riskLevel <= config.maxRisk &&
            opportunity.confidence >= config.minConfidence &&
            opportunity.strategy in config.enabledStrategies

    private suspend fun enqueue(opportunity: MevOpportunity) {
        if (active.size >= config.maxOpportunities) return
        queue.addLast(opportunity)
        active[opportunity.id] = opportunity
        totalOpportunities++
        publish(MevEventTypes.OPPORTUNITY_QUEUED, opportunity)
    }

    private suspend fun execute(opportunity: MevOpportunity) {
        try {
            val result = strategyService.executeStrategy(opportunity.copy(status = MevOpportunityStatus.EXECUTING))
            record(opportunity.strategy, result)
            val done = opportunity.copy(status = if (result.success) MevOpportunityStatus.COMPLETED else MevOpportunityStatus.FAILED)
            publish(MevEventTypes.OPPORTUNITY_EXECUTED, done, result)
        } finally {
            active.remove(opportunity.id)
            detector.removeOpportunity(opportunity.id)
        }
    }

    private fun record(
        strategy: MevStrategyType,
        result: StrategyExecutionResult,
    ) {
        executedCount++
        attemptsByStrategy.merge(strategy, 1, Int::plus)
        if (result.success) {
            totalProfit += result.profit
            totalGasSpent += result.gasUsed
            netProfit += result.netProfit
            successesByStrategy.merge(strategy, 1, Int::plus)
            profitByStrategy.merge(strategy, result.profit, Double::plus)
            successfulAttacks++
            botProfit += result.netProfit
        } else {
            failedAttacks++
        }
        statsEnd = clock.nowMillis()
        lastActivity = statsEnd
    }

    private suspend fun publish(
        type: String,
        opportunity: MevOpportunity? = null,
        result: StrategyExecutionResult? = null,
    ) {
        val now = clock.nowMillis()
        events.publish(MevBotEvent(EventIds.next(now), type, opportunity, result, now))
    }

    companion object {
        val PROCESS_INTERVAL: Duration = Duration.ofSeconds(1)
        private val ERROR_BACKOFF: Duration = Duration.ofSeconds(5)
    }
}

data class QueueStatus(
    val queueLength: Int,
    val isProcessing: Boolean,
)
