package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.shared.pool.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Random user traffic for the mempool, ported from the original
 * `TransactionGeneratorService`. Distributions are kept as in the original.
 */
@Service
class TransactionGeneratorService(
    private val sim: SimulationContext,
    private val transactionPool: TransactionPoolService,
    private val gasService: GasService,
    private val random: Random,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var generator: Job? = null
    private var transactionCounter = 0L
    private var userCounter = 0L

    suspend fun startGenerating() =
        sim.run {
            if (generator != null) return@run
            generator =
                sim.scope.launch {
                    while (true) {
                        delay(INTERVAL_MILLIS)
                        // The original stopped for good after the first failure (e.g. a full
                        // pool) while still reporting itself as running.
                        try {
                            generateRandomTransaction()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("트랜잭션 생성 실패: {}", e.message)
                        }
                    }
                }
            log.info("트랜잭션 자동 생성 시작 (1초 간격)")
        }

    suspend fun stopGenerating() =
        sim.run {
            generator?.cancel()
            generator = null
            log.info("트랜잭션 자동 생성 중지")
        }

    suspend fun isActive(): Boolean = sim.run { generator != null }

    suspend fun getStats(): TransactionGeneratorStats =
        sim.run { TransactionGeneratorStats(generator != null, transactionCounter, userCounter) }

    suspend fun generateRandomTransaction(): Transaction =
        sim.run {
            transactionCounter++
            val type = selectTransactionType()
            val pool = POOLS[random.nextInt(POOLS.size)]
            val zeroForOne = random.nextBoolean()
            val tokenIn = if (zeroForOne) pool.tokenA else pool.tokenB
            val tokenOut = if (zeroForOne) pool.tokenB else pool.tokenA
            val amountIn = amountIn(tokenIn, selectSizeInEth())
            val gasPrice = selectGasPrice()
            val user = "user-${random.nextInt(USER_COUNT)}"
            if (random.nextDouble() > NEW_USER_PROBABILITY) userCounter++

            val callData =
                SwapAbi.encode(
                    SwapCallArgs(
                        recipient = user,
                        // The original drew this independently of the input token.
                        zeroForOne = zeroForOne,
                        amountSpecified = amountIn.unscaledValue().toString(),
                        sqrtPriceLimitX96 = "0",
                        data = "0x",
                    ),
                )
            val now = clock.nowMillis()
            val transaction =
                transactionPool.submit(
                    Transaction(
                        id = "tx_${now.toEpochMilli()}_$transactionCounter",
                        type = type,
                        from = user,
                        to = pool.address,
                        value = "0",
                        data = callData,
                        gasPrice = gasPrice,
                        gasLimit = gasService.estimateGas(type),
                        status = TransactionStatus.PENDING,
                        nonce = transactionPool.getCurrentNonce(user),
                        timestamp = now,
                        parsedData = SwapAbi.decode(callData),
                    ),
                )
            log.debug("트랜잭션 생성: {} {}({}) → {}, gasPrice: {}", pool.pair, tokenIn, amountIn.toPlainString(), tokenOut, gasPrice)
            transaction
        }

    private fun selectTransactionType(): TransactionType {
        val roll = random.nextDouble()
        return when {
            roll < 0.85 -> TransactionType.SWAP
            roll < 0.95 -> TransactionType.ADD_LIQUIDITY
            else -> TransactionType.REMOVE_LIQUIDITY
        }
    }

    /** Trade size in ETH: mostly small, rarely whale sized. */
    private fun selectSizeInEth(): Double {
        val roll = random.nextDouble()
        return when {
            roll < 0.5 -> 0.1 + random.nextDouble() * 1.9
            roll < 0.75 -> 2 + random.nextDouble() * 3
            roll < 0.9 -> 5 + random.nextDouble() * 15
            roll < 0.97 -> 20 + random.nextDouble() * 30
            else -> 50 + random.nextDouble() * 150
        }
    }

    /** Whole gwei. The original produced fractional gwei values. */
    private fun selectGasPrice(): Long {
        val roll = random.nextDouble()
        val price =
            when {
                roll < 0.5 -> 80 + random.nextDouble() * 40
                roll < 0.8 -> 120 + random.nextDouble() * 80
                else -> 200 + random.nextDouble() * 200
            }
        return price.toLong()
    }

    /** Converts an ETH-denominated size into [token], rounded to 6 decimals then to the token scale. */
    private fun amountIn(
        token: String,
        sizeInEth: Double,
    ): BigDecimal {
        val priceInEth = PRICES_IN_ETH.getValue(token)
        val amount = BigDecimal.valueOf(sizeInEth / priceInEth).setScale(SIZE_DECIMALS, RoundingMode.HALF_UP)
        return Tokens.scale(amount, token)
    }

    private data class GeneratorPool(
        val pair: String,
        val address: String,
        val tokenA: String,
        val tokenB: String,
    )

    companion object {
        private const val INTERVAL_MILLIS = 1_000L
        private const val USER_COUNT = 100
        private const val NEW_USER_PROBABILITY = 0.9
        private const val SIZE_DECIMALS = 6

        private val POOLS =
            listOf(
                GeneratorPool("ETH_USDC", "0x8ad599c3A0ff1De082011EFDDc58f1908eb6e6D8", "ETH", "USDC"),
                GeneratorPool("BTC_ETH", "0x4585FE77225b41b697C938B018E2Ac67Ac5a20c0", "BTC", "ETH"),
                GeneratorPool("DAI_USDC", "0x5777d92f208679DB4b9778590Fa3CAB3aC9e2168", "DAI", "USDC"),
                GeneratorPool("WETH_USDC", "0x88e6A0c2dDD26FEEb64F039a2c41296FcB3f5640", "WETH", "USDC"),
            )

        private val PRICES_IN_ETH =
            mapOf("ETH" to 1.0, "WETH" to 1.0, "BTC" to 20.0, "USDC" to 0.0005, "DAI" to 0.0005)
    }
}
