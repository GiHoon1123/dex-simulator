package io.github.gihoon1123.dexsimulator.shared.blockchain

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Starts block production and the transaction generator once the app is ready, like the
 * original `main.ts` did outside production. Turned off with `dex.simulation.auto-start=false`
 * (tests do this). The MEV bot is added when mev-simulation is migrated.
 */
@Component
@ConditionalOnProperty(name = ["dex.simulation.auto-start"], havingValue = "true")
class SimulationAutoStart(
    private val blockService: BlockService,
    private val generator: TransactionGeneratorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        runBlocking {
            blockService.startAutoProduction()
            generator.startGenerating()
            log.info("자동 블록 생성, 트랜잭션 자동 생성 시작됨")
        }
}
