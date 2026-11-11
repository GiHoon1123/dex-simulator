package io.github.gihoon1123.dexsimulator.mev

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.blockchain.Transaction
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionPoolService
import io.github.gihoon1123.dexsimulator.shared.blockchain.TransactionType
import io.github.gihoon1123.dexsimulator.shared.pool.PoolService
import io.github.gihoon1123.dexsimulator.shared.pool.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scans the mempool for large swaps to attack, ported from the original `MevDetectorService`.
 *
 * Fixed in the migration (decisions #13): the original checked "already analyzed" against
 * opportunity ids instead of transaction ids, so every pending swap became a new
 * opportunity every second; it read every amount as 18-decimal ETH; and it divided gwei
 * gas prices by 1e9 again, so the gas risk was always 0.
 */
@Service
class MevDetectorService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val transactionPool: TransactionPoolService,
    private val poolService: PoolService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val opportunities = LinkedHashMap<String, MevOpportunity>()
    private val analyzedTransactions = HashSet<String>()
    private var criteria = DEFAULT_CRITERIA
    private var monitoring: Job? = null

    /** Starts a scan every second with criteria derived from [config]. */
    suspend fun startDetection(config: MevBotConfig) =
        sim.run {
            if (monitoring != null) return@run
            criteria =
                DetectionCriteria(
                    minTransactionValue = maxOf(config.minProfit * 2, 0.1),
                    minGasPrice = 50,
                    minSlippage = 0.5,
                    maxPoolImpact = 10.0,
                    minProfitThreshold = maxOf(config.minProfit, 0.01),
                )
            monitoring =
                sim.scope.launch {
                    while (true) {
                        delay(SCAN_INTERVAL.toMillis())
                        try {
                            scanPendingTransactions()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.error("트랜잭션 스캔 중 오류 발생", e)
                        }
                    }
                }
            log.info("MEV 기회 감지가 시작되었습니다")
        }

    suspend fun stopDetection() =
        sim.run {
            monitoring?.cancel()
            monitoring = null
            log.info("MEV 기회 감지가 중지되었습니다")
        }

    suspend fun isDetectionActive(): Boolean = sim.run { monitoring != null }

    /** One scan: drops expired opportunities and analyzes each pending transaction once. */
    suspend fun scanPendingTransactions(): List<MevOpportunity> =
        sim.run {
            val now = clock.nowMillis()
            opportunities.values.removeIf { it.expiresAt.isBefore(now) }
            val pending = transactionPool.getPendingTransactions()
            analyzedTransactions.retainAll(pending.map { it.id }.toSet())

            pending.filter { analyzedTransactions.add(it.id) }.mapNotNull { tx ->
                detect(tx)?.also { opportunity ->
                    opportunities[opportunity.id] = opportunity
                    events.publish(MevBotEvent(EventIds.next(now), MevEventTypes.OPPORTUNITY_DETECTED, opportunity, timestamp = now))
                }
            }
        }

    suspend fun getOpportunities(): List<MevOpportunity> = sim.run { opportunities.values.toList() }

    suspend fun getOpportunity(id: String): MevOpportunity? = sim.run { opportunities[id] }

    suspend fun removeOpportunity(id: String): Boolean = sim.run { opportunities.remove(id) != null }

    suspend fun updateDetectionCriteria(update: (DetectionCriteria) -> DetectionCriteria) = sim.run { criteria = update(criteria) }

    suspend fun getDetectionCriteria(): DetectionCriteria = sim.run { criteria }

    private suspend fun detect(tx: Transaction): MevOpportunity? {
        if (tx.type != TransactionType.SWAP) return null
        val value = swapValueInEth(tx) ?: return null
        if (value < criteria.minTransactionValue || tx.gasPrice < criteria.minGasPrice) return null

        // Only candidates that clear the profit threshold are considered, as in the original.
        val best =
            STRATEGIES
                .map { it to it.netProfit(value) }
                .filter { (_, net) -> net >= criteria.minProfitThreshold }
                .maxByOrNull { (_, net) -> net }
                ?.first ?: return null
        val confidence =
            STRATEGIES.filter { it.netProfit(value) >= criteria.minProfitThreshold }.maxOf { it.successProbability }

        val now = clock.nowMillis()
        return MevOpportunity(
            id = "mev_${tx.id}_${now.toEpochMilli()}",
            targetTransactionId = tx.id,
            targetPoolAddress = tx.to,
            strategy = best.type,
            estimatedProfit = value * best.profitRate,
            riskLevel = riskScore(tx),
            gasCost = best.gasCost,
            netProfit = best.netProfit(value),
            confidence = confidence,
            status = MevOpportunityStatus.DETECTED,
            detectedAt = now,
            expiresAt = now.plus(OPPORTUNITY_TTL),
        )
    }

    /**
     * ETH value of the victim's input: minimal units scaled by the input token's decimals and
     * converted with the simulator's mock ETH prices. Null when the call cannot be read.
     */
    private suspend fun swapValueInEth(tx: Transaction): Double? {
        val params = tx.parsedData?.params ?: return null
        val pool = poolService.getPool(tx.to) ?: return null
        val tokenIn = if (params.zeroForOne) pool.tokenA else pool.tokenB
        val amount = BigDecimal(params.amountSpecified).movePointLeft(Tokens.decimals(tokenIn)).toDouble()
        return amount * (PRICES_IN_ETH[tokenIn] ?: 1.0)
    }

    /** Gas price risk (gwei / 100) plus liquidity risk against the assumed 1000 ETH pool, capped at 10. */
    private fun riskScore(tx: Transaction): Double {
        val gasRisk = minOf(tx.gasPrice / 100.0, 10.0)
        val liquidityRisk = maxOf(0.0, 10 - ASSUMED_POOL_LIQUIDITY_ETH / 100)
        return minOf(gasRisk + liquidityRisk, 10.0)
    }

    /** Fixed profit and gas assumptions of the original analysis. */
    private data class StrategyAssumption(
        val type: MevStrategyType,
        val profitRate: Double,
        val gasCost: Double,
        val successProbability: Double,
    ) {
        fun netProfit(value: Double) = value * profitRate - gasCost
    }

    companion object {
        val SCAN_INTERVAL: Duration = Duration.ofSeconds(1)
        val OPPORTUNITY_TTL: Duration = Duration.ofSeconds(30)

        /** The original's placeholder pool size for risk, kept as is. */
        private const val ASSUMED_POOL_LIQUIDITY_ETH = 1000.0

        private val DEFAULT_CRITERIA = DetectionCriteria(5.0, 100, 0.5, 10.0, 0.05)

        private val STRATEGIES =
            listOf(
                StrategyAssumption(MevStrategyType.FRONT_RUN, profitRate = 0.02, gasCost = 0.003, successProbability = 0.7),
                StrategyAssumption(MevStrategyType.BACK_RUN, profitRate = 0.015, gasCost = 0.002, successProbability = 0.6),
                StrategyAssumption(MevStrategyType.SANDWICH, profitRate = 0.03, gasCost = 0.005, successProbability = 0.5),
            )

        /** Same mock prices as the transaction generator. */
        private val PRICES_IN_ETH = mapOf("ETH" to 1.0, "WETH" to 1.0, "BTC" to 20.0, "USDC" to 0.0005, "DAI" to 0.0005)
    }
}
