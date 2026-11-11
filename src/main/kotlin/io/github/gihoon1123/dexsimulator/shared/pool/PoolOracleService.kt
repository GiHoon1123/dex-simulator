package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.event.EventIds
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.events.MevOpportunityDetectedEvent
import io.github.gihoon1123.dexsimulator.shared.events.MevOpportunityExpiredEvent
import io.github.gihoon1123.dexsimulator.shared.events.MevPriority
import io.github.gihoon1123.dexsimulator.shared.events.OracleStatusChangedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolCreatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolUpdatedEvent
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pool price oracle, ported from the original `PoolOracleService`.
 *
 * Fixed in the migration (docs/decisions.md #9): the original never registered any pool,
 * so prices, TWAP and MEV detection were always empty, and stored prices were never
 * refreshed from reserves. Here pools are registered from pool events and every pool
 * update refreshes the price.
 */
@Service
class PoolOracleService(
    private val sim: SimulationContext,
    private val events: EventBus,
    private val poolService: PoolService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val poolPrices = LinkedHashMap<String, PoolPrice>()
    private val priceHistory = HashMap<String, ArrayDeque<PriceHistoryItem>>()

    /** Keyed by pool address: at most one active opportunity per pool, as in the original. */
    private val activeOpportunities = LinkedHashMap<String, MevOpportunity>()
    private var config = OracleConfig()
    private var status = OracleStatus(false, clock.nowMillis(), 0, 0, 0, 0.0, OracleSystemStatus.HEALTHY)
    private var jobs: List<Job> = emptyList()

    @PostConstruct
    fun startOnBoot() = runBlocking(sim.dispatcher) { start() }

    /** Starts price tracking and the periodic jobs. Does nothing when already active. */
    suspend fun start() =
        sim.run {
            if (status.isActive) return@run
            status = status.copy(isActive = true, systemStatus = OracleSystemStatus.HEALTHY)
            jobs =
                listOf(
                    events.subscribe<PoolCreatedEvent>(sim.scope, onSubscribed = { registerExistingPools() }) {
                        registerPool(it.pool)
                    },
                    events.subscribe<PoolUpdatedEvent>(sim.scope) { refreshPrice(it.currentState) },
                    // The interval is read on every round, so a config change takes effect
                    // from the next round. The original kept the interval it started with.
                    repeating({ Duration.ofSeconds(config.updateInterval.toLong()) }) { performPeriodicUpdate() },
                    repeating({ EXPIRATION_CHECK_INTERVAL }) { checkExpiredOpportunities() },
                )
            log.info("풀 오라클 서비스가 시작되었습니다")
            publishStatusChanged("inactive", "active", "Oracle service started")
        }

    suspend fun stop() =
        sim.run {
            if (!status.isActive) return@run
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            status = status.copy(isActive = false)
            publishStatusChanged("active", "inactive", "Oracle service stopped")
            log.info("풀 오라클 서비스가 중지되었습니다")
        }

    /** One round of the periodic job: record prices, detect opportunities, refresh stats. */
    suspend fun performPeriodicUpdate() =
        sim.run {
            try {
                status = status.copy(lastUpdate = clock.nowMillis())
                poolPrices.keys.toList().forEach { recordPrice(it) }
                detectMevOpportunities()
                updateStatistics()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("정기 업데이트 중 오류 발생", e)
                status = status.copy(systemStatus = OracleSystemStatus.ERROR)
            }
        }

    suspend fun checkExpiredOpportunities() =
        sim.run {
            val now = clock.nowMillis()
            activeOpportunities.values.filter { now.isAfter(it.expiresAt) }.forEach { opportunity ->
                activeOpportunities.remove(opportunity.poolAddress)
                events.publish(
                    MevOpportunityExpiredEvent(EventIds.next(now), opportunity.opportunityId, opportunity.poolAddress, now, "timeout"),
                )
                log.info("MEV 기회 만료: {}", opportunity.poolAddress)
            }
        }

    suspend fun calculateTwap(
        poolAddress: String,
        period: Int? = null,
    ): TwapInfo? =
        sim.run {
            val periodSeconds = period ?: config.twapPeriod
            val now = clock.nowMillis()
            val startTime = now.minusSeconds(periodSeconds.toLong())
            val samples = priceHistory[poolAddress]?.filter { !it.timestamp.isBefore(startTime) }.orEmpty()
            if (samples.size < 2) return@run null

            // Each price is weighted by the seconds since the previous sample.
            val weighted =
                samples.zipWithNext { previous, current ->
                    val weight = Duration.between(previous.timestamp, current.timestamp).toMillis() / 1000.0
                    current.price * weight to weight
                }
            val totalWeight = weighted.sumOf { it.second }
            TwapInfo(
                poolAddress = poolAddress,
                twapPrice = if (totalWeight > 0) weighted.sumOf { it.first } / totalWeight else 0.0,
                period = periodSeconds,
                sampleCount = samples.size,
                startTime = startTime,
                endTime = now,
                totalWeight = totalWeight,
            )
        }

    suspend fun calculatePriceDeviation(
        poolAddress: String,
        externalPrice: Double,
    ): PriceDeviation? = sim.run { deviationOf(poolAddress, externalPrice) }

    suspend fun getPoolPrice(poolAddress: String): PoolPrice? = sim.run { poolPrices[poolAddress] }

    suspend fun getAllPoolPrices(): List<PoolPrice> = sim.run { poolPrices.values.toList() }

    suspend fun getActiveOpportunities(filter: MevOpportunityFilter = MevOpportunityFilter()): List<MevOpportunity> =
        sim.run {
            activeOpportunities.values.filter { opportunity ->
                (filter.poolAddress == null || opportunity.poolAddress == filter.poolAddress) &&
                    (filter.strategy == null || opportunity.strategy == filter.strategy) &&
                    (filter.minProfit == null || opportunity.expectedProfit >= filter.minProfit) &&
                    (filter.maxRisk == null || opportunity.riskLevel <= filter.maxRisk) &&
                    (!filter.executableOnly || opportunity.isExecutable)
            }
        }

    suspend fun getOpportunity(opportunityId: String): MevOpportunity? =
        sim.run { activeOpportunities.values.firstOrNull { it.opportunityId == opportunityId } }

    suspend fun getOracleStatus(): OracleStatus = sim.run { status }

    suspend fun updateConfig(update: (OracleConfig) -> OracleConfig) =
        sim.run {
            config = update(config)
            log.info("오라클 설정이 업데이트되었습니다")
        }

    suspend fun getConfig(): OracleConfig = sim.run { config }

    private suspend fun registerExistingPools() = poolService.getAllPools().forEach { registerPool(it) }

    private fun registerPool(pool: PoolState) {
        if (poolPrices.containsKey(pool.address)) {
            refreshPrice(pool)
            return
        }
        val price = pool.price()
        poolPrices[pool.address] =
            PoolPrice(pool.address, pool.pair, pool.tokenA, pool.tokenB, price, 1 / price, clock.nowMillis(), INITIAL_CONFIDENCE)
        priceHistory[pool.address] = ArrayDeque()
        updateStatistics()
        log.info("풀 등록됨: {} ({})", pool.address, pool.pair)
    }

    private fun refreshPrice(pool: PoolState) {
        val current = poolPrices[pool.address] ?: return
        val price = pool.price()
        poolPrices[pool.address] = current.copy(price = price, inversePrice = 1 / price, timestamp = clock.nowMillis())
    }

    private fun recordPrice(poolAddress: String) {
        val current = poolPrices[poolAddress] ?: return
        val now = clock.nowMillis()
        val history = priceHistory.getOrPut(poolAddress) { ArrayDeque() }
        history.addLast(PriceHistoryItem(current.price, now, volume = 0.0, weight = 1.0))
        if (history.size > MAX_HISTORY) history.removeFirst()
        poolPrices[poolAddress] = current.copy(confidence = confidenceOf(history), timestamp = now)
    }

    private suspend fun detectMevOpportunities() {
        for ((poolAddress, poolPrice) in poolPrices) {
            val externalPrice = EXTERNAL_PRICES[poolPrice.pair] ?: continue
            val deviation = deviationOf(poolAddress, externalPrice) ?: continue
            if (!deviation.isMEVOpportunity || activeOpportunities.containsKey(poolAddress)) continue

            val opportunity = opportunityOf(poolPrice, deviation)
            activeOpportunities[poolAddress] = opportunity
            events.publish(
                MevOpportunityDetectedEvent(
                    eventId = EventIds.next(opportunity.detectedAt),
                    mevOpportunity = opportunity,
                    timestamp = opportunity.detectedAt,
                    priority = MevPriority.of(opportunity.expectedProfit),
                    autoExecutable = opportunity.isExecutable && config.autoExecute,
                ),
            )
            log.info("MEV 기회 탐지: {} - {}% 편차", poolAddress, String.format(java.util.Locale.ROOT, "%.2f", deviation.relativeDifference))
        }
    }

    private fun deviationOf(
        poolAddress: String,
        externalPrice: Double,
    ): PriceDeviation? {
        val poolPrice = poolPrices[poolAddress]?.price ?: return null
        val absoluteDifference = abs(poolPrice - externalPrice)
        val relativeDifference = absoluteDifference / externalPrice * 100
        val direction =
            when {
                poolPrice > externalPrice -> DeviationDirection.POOL_HIGHER
                poolPrice < externalPrice -> DeviationDirection.EXTERNAL_HIGHER
                else -> DeviationDirection.EQUAL
            }
        return PriceDeviation(
            poolAddress = poolAddress,
            poolPrice = poolPrice,
            externalPrice = externalPrice,
            absoluteDifference = absoluteDifference,
            relativeDifference = relativeDifference,
            direction = direction,
            detectedAt = clock.nowMillis(),
            isMEVOpportunity = relativeDifference >= config.mevThreshold,
        )
    }

    private fun opportunityOf(
        poolPrice: PoolPrice,
        deviation: PriceDeviation,
    ): MevOpportunity {
        val now = clock.nowMillis()
        val expectedProfit = deviation.relativeDifference * PROFIT_SHARE_OF_DEVIATION
        val expectedProfitUsd = expectedProfit * ASSUMED_TRADE_USD
        val netProfit = expectedProfitUsd - GAS_COST_USD
        val difference = deviation.relativeDifference
        return MevOpportunity(
            opportunityId = "mev_${poolPrice.poolAddress}_${now.toEpochMilli()}",
            poolAddress = poolPrice.poolAddress,
            pair = poolPrice.pair,
            strategy =
                when {
                    difference > 5 -> MevStrategy.ARBITRAGE
                    difference > 2 -> MevStrategy.SANDWICH
                    difference > 1 -> MevStrategy.FRONT_RUN
                    else -> MevStrategy.BACK_RUN
                },
            expectedProfit = expectedProfit,
            expectedProfitUSD = expectedProfitUsd,
            gasCost = GAS_COST_USD,
            netProfit = netProfit,
            detectedAt = now,
            expiresAt = now.plus(OPPORTUNITY_TTL),
            isExecutable = netProfit > 0,
            riskLevel =
                when {
                    difference > 10 -> 5
                    difference > 5 -> 4
                    difference > 2 -> 3
                    difference > 1 -> 2
                    else -> 1
                },
        )
    }

    /** 1 - coefficient of variation of the last 10 prices, clamped to 0..1. */
    private fun confidenceOf(history: ArrayDeque<PriceHistoryItem>): Double {
        if (history.size < 2) return DEFAULT_CONFIDENCE
        val prices = history.takeLast(CONFIDENCE_WINDOW).map { it.price }
        val mean = prices.average()
        val variance = prices.sumOf { (it - mean).pow(2) } / prices.size
        return (1 - sqrt(variance) / mean).coerceIn(0.0, 1.0)
    }

    private fun updateStatistics() {
        status =
            status.copy(
                totalPools = poolPrices.size,
                monitoredPools = poolPrices.size,
                activeOpportunities = activeOpportunities.size,
                averageConfidence =
                    poolPrices.values
                        .map { it.confidence }
                        .average()
                        .takeUnless { it.isNaN() } ?: 0.0,
            )
    }

    private suspend fun publishStatusChanged(
        previous: String,
        current: String,
        reason: String,
    ) {
        val now = clock.nowMillis()
        events.publish(OracleStatusChangedEvent(EventIds.next(now), previous, current, now, reason))
    }

    private fun repeating(
        interval: () -> Duration,
        action: suspend () -> Unit,
    ): Job =
        sim.scope.launch {
            while (true) {
                delay(interval().toMillis())
                action()
            }
        }

    companion object {
        private const val INITIAL_CONFIDENCE = 1.0
        private const val DEFAULT_CONFIDENCE = 0.5
        private const val CONFIDENCE_WINDOW = 10
        private const val MAX_HISTORY = 1000
        private const val PROFIT_SHARE_OF_DEVIATION = 0.1
        private const val ASSUMED_TRADE_USD = 1000.0
        private const val GAS_COST_USD = 0.01
        private val OPPORTUNITY_TTL: Duration = Duration.ofSeconds(60)
        private val EXPIRATION_CHECK_INTERVAL: Duration = Duration.ofSeconds(10)

        /**
         * Mock external prices (token B per token A). BTC/ETH was 0.05 in the original,
         * which is ETH/BTC inverted; the pool itself prices BTC at 20 ETH.
         */
        val EXTERNAL_PRICES =
            mapOf(
                "ETH/USDC" to 2000.0,
                "BTC/ETH" to 20.0,
                "DAI/USDC" to 1.0,
                "WETH/USDC" to 2000.0,
            )
    }
}
