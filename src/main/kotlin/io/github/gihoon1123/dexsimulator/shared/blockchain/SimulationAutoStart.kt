package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.mev.MevBotService
import io.github.gihoon1123.dexsimulator.mev.MevStrategyType
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts block production and the transaction generator once the app is ready, like the
 * original `main.ts` did outside production. Turned off with `dex.simulation.auto-start=false`
 * (tests do this). The MEV bot starts with the original `main.ts` config, except `maxRisk`
 * (decisions #13).
 */
@Component
@ConditionalOnProperty(name = ["dex.simulation.auto-start"], havingValue = "true")
class SimulationAutoStart(
    private val blockService: BlockService,
    private val generator: TransactionGeneratorService,
    private val mevBot: MevBotService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        runBlocking {
            blockService.startAutoProduction()
            generator.startGenerating()
            mevBot.startBot {
                it.copy(
                    minProfit = 0.01,
                    // 0.8 in the original, where it was never effective; risk is a 1..10 score.
                    maxRisk = 8.0,
                    gasPriceMultiplier = 1.5,
                    maxOpportunities = 10_000_000_000,
                    opportunityTimeout = 30_000,
                    minConfidence = 0.7,
                    enabledStrategies = MevStrategyType.entries,
                )
            }
            log.info("자동 블록 생성, 트랜잭션 자동 생성, MEV 봇 시작됨")
        }
}
