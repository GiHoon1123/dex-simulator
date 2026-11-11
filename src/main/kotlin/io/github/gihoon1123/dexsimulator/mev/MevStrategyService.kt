package io.github.gihoon1123.dexsimulator.mev

import io.github.gihoon1123.dexsimulator.shared.blockchain.SwapAbi
import io.github.gihoon1123.dexsimulator.shared.blockchain.SwapCallArgs
import org.springframework.stereotype.Service
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Executes an MEV strategy, ported from the original `MevStrategyService`.
 *
 * As in the original, execution is simulated: the bot builds its transactions but does not
 * send them to the mempool, and profit comes from the opportunity's estimate. The
 * generated call data is real ABI here (the original encoded zeros) and gas prices are in
 * gwei like the rest of the simulator (the original used wei only here).
 */
@Service
class MevStrategyService(
    private val random: Random,
) {
    fun executeStrategy(opportunity: MevOpportunity): StrategyExecutionResult {
        val started = System.nanoTime()
        val plan = PLANS.getValue(opportunity.strategy)
        if (!canExecute(opportunity)) {
            return failure(opportunity, "전략 실행 조건을 만족하지 않습니다", elapsedMillis(started))
        }
        // 95% expected realization, slightly reduced by risk.
        val profit = opportunity.estimatedProfit * 0.95 * (1 - (opportunity.riskLevel / 10) * 0.05)
        val netProfit = profit - plan.gasCost
        return StrategyExecutionResult(
            strategy = opportunity.strategy,
            success = netProfit > 0,
            profit = profit,
            gasUsed = plan.gasCost,
            netProfit = netProfit,
            executionTime = elapsedMillis(started),
            transactions = SubmittedTransactions(transactionsFor(opportunity, plan), emptyList(), emptyList()),
            metadata = StrategyMetadata(opportunity.targetPoolAddress, opportunity.targetTransactionId, plan.params),
        )
    }

    private fun canExecute(opportunity: MevOpportunity) =
        opportunity.estimatedProfit > 0.001 && opportunity.riskLevel < 10 && opportunity.confidence > 0.3

    private fun transactionsFor(
        opportunity: MevOpportunity,
        plan: StrategyPlan,
    ): List<MevTransaction> =
        plan.legs.map { leg ->
            MevTransaction(
                to = opportunity.targetPoolAddress,
                value = if (leg.buy) leg.amountWei.toString() else "0",
                data = SwapAbi.encode(SwapCallArgs(BOT_ADDRESS, leg.buy, leg.amountWei.toString(), "0", "0x")),
                gasPrice = leg.gasPriceGwei,
                gasLimit = GAS_LIMIT,
                // Placeholder, as in the original: the bot does not submit to the mempool.
                nonce = random.nextLong(PLACEHOLDER_NONCE_BOUND),
            )
        }

    private fun failure(
        opportunity: MevOpportunity,
        message: String,
        executionTime: Long,
    ) = StrategyExecutionResult(
        strategy = opportunity.strategy,
        success = false,
        profit = 0.0,
        gasUsed = 0.0,
        netProfit = 0.0,
        executionTime = executionTime,
        transactions = SubmittedTransactions(emptyList(), emptyList(), emptyList()),
        metadata = StrategyMetadata(opportunity.targetPoolAddress, opportunity.targetTransactionId, emptyMap()),
        errorMessage = message,
    )

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000

    private data class Leg(
        val buy: Boolean,
        val amountEth: String,
        val gasPriceGwei: Long,
    ) {
        val amountWei = BigDecimal(amountEth).movePointRight(18).toBigIntegerExact()
    }

    private data class StrategyPlan(
        val gasCost: Double,
        val legs: List<Leg>,
        val params: Map<String, Double>,
    )

    companion object {
        const val BOT_ADDRESS = "mev-bot"
        private const val GAS_LIMIT = 200_000L
        private const val PLACEHOLDER_NONCE_BOUND = 1_000_000L

        /** Gas costs, leg sizes and gas prices of the original strategies. */
        private val PLANS =
            mapOf(
                MevStrategyType.FRONT_RUN to
                    StrategyPlan(
                        gasCost = 0.003,
                        legs =
                            listOf(
                                Leg(buy = true, amountEth = "1", gasPriceGwei = 250),
                                Leg(buy = false, amountEth = "1", gasPriceGwei = 200),
                            ),
                        params = mapOf("buyAmount" to 1.0, "sellThreshold" to 0.02),
                    ),
                MevStrategyType.BACK_RUN to
                    StrategyPlan(
                        gasCost = 0.002,
                        legs = listOf(Leg(buy = true, amountEth = "0.5", gasPriceGwei = 180)),
                        params = mapOf("buyAmount" to 0.5, "waitTime" to 1000.0),
                    ),
                MevStrategyType.SANDWICH to
                    StrategyPlan(
                        gasCost = 0.005,
                        legs =
                            listOf(
                                Leg(buy = true, amountEth = "2", gasPriceGwei = 300),
                                Leg(buy = false, amountEth = "2", gasPriceGwei = 280),
                            ),
                        params = mapOf("frontRunAmount" to 2.0, "backRunAmount" to 2.0),
                    ),
            )
    }
}
