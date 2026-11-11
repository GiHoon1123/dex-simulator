package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.shared.events.PoolTransactionCreatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.PoolUpdateReason
import io.github.gihoon1123.dexsimulator.shared.events.PoolUpdatedEvent
import io.github.gihoon1123.dexsimulator.shared.events.SwapExecutedEvent
import io.github.gihoon1123.dexsimulator.support.EventRecorder
import io.github.gihoon1123.dexsimulator.support.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

class PoolServiceTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val service = PoolService(sim, bus, clock).apply { initializeDefaultPools() }

    @AfterEach
    fun tearDown() = sim.close()

    private fun dec(value: String) = BigDecimal(value)

    /** Compares by value, ignoring scale (1000 == 1000.000000). */
    private fun assertAmount(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was ${actual.toPlainString()}")

    private fun swap(
        amountIn: String = "1",
        tokenIn: String = "ETH",
        amountOutMin: String = "0",
        deadline: Long = clock.instant().epochSecond + 60,
    ) = SwapParams(
        ETH_USDC,
        tokenIn,
        if (tokenIn ==
            "ETH"
        ) {
            "USDC"
        } else {
            "ETH"
        },
        dec(amountIn),
        dec(amountOutMin),
        "user-1",
        deadline,
        100,
        200_000,
    )

    @Test
    fun `creates the four default pools at token scale`() =
        runBlocking {
            val pools = service.getAllPools()

            assertEquals(listOf("ETH/USDC", "BTC/ETH", "DAI/USDC", "WETH/USDC"), pools.map { it.pair })
            val ethUsdc = pools.first()
            assertEquals(dec("1000.000000000000000000"), ethUsdc.reserveA)
            assertEquals(dec("2000000.000000"), ethUsdc.reserveB)
            assertEquals(2000.0, service.getPoolStats(ETH_USDC)!!.currentPrice)
            assertEquals(4_000_000.0, service.getPoolStats(ETH_USDC)!!.totalLiquidity)
        }

    @Test
    fun `duplicate pool address is rejected instead of overwritten`() =
        runBlocking {
            val params = PoolService.DEFAULT_POOLS.first()

            val error = assertThrows<ApiException> { service.createPool(params) }
            assertEquals(HttpStatus.CONFLICT, error.status)
        }

    @Test
    fun `swap moves reserves by the quoted amounts and keeps k from shrinking`() =
        runBlocking {
            val before = service.getPool(ETH_USDC)!!

            val result = service.executeSwap(swap())

            assertTrue(result.success)
            assertEquals("swap_1_${clock.millis()}", result.swapId)
            assertEquals(dec("1992.013962"), result.amountOut)
            assertEquals(dec("0.003000000000000000"), result.fee)
            assertEquals(200_000L * 100, result.gasCost)
            val after = service.getPool(ETH_USDC)!!
            assertEquals(before.reserveA + dec("1"), after.reserveA)
            assertEquals(before.reserveB - dec("1992.013962"), after.reserveB)
            assertEquals(after.reserveA * after.reserveB, after.k)
            assertTrue(after.k >= before.k)
            assertEquals(dec("1.000000000000000000"), after.totalVolume)
        }

    @Test
    fun `swap publishes transaction, swap and pool update events`() =
        runBlocking {
            val recorder = EventRecorder(bus).start()

            val result = service.executeSwap(swap())

            recorder.awaitUntil { it.size >= 3 }
            val transaction = recorder.ofType<PoolTransactionCreatedEvent>().single().transaction
            assertEquals(result.swapId, transaction.id)
            assertTrue(transaction.swapParams.zeroForOne)
            assertEquals("1", transaction.swapParams.amountSpecified)
            assertEquals(result, recorder.ofType<SwapExecutedEvent>().single().swapResult)
            val updated = recorder.ofType<PoolUpdatedEvent>().single()
            assertEquals(PoolUpdateReason.SWAP, updated.reason)
            assertNotNull(updated.changes.reserveA)
            assertNotNull(updated.changes.totalVolume)
            recorder.stop()
        }

    @Test
    fun `swap below the minimum output fails without touching the pool`() =
        runBlocking {
            val before = service.getPool(ETH_USDC)

            val result = service.executeSwap(swap(amountOutMin = "1993"))

            assertFalse(result.success)
            assertEquals(PoolMessages.MIN_OUTPUT_NOT_MET, result.error)
            assertEquals(before, service.getPool(ETH_USDC))
        }

    @Test
    fun `expired deadline is reported`() =
        runBlocking {
            val simulation = service.simulateSwap(swap(deadline = clock.instant().epochSecond))

            assertFalse(simulation.isExecutable)
            assertEquals(listOf(PoolMessages.DEADLINE_EXPIRED), simulation.errors)
        }

    @Test
    fun `unknown pool and foreign token are rejected`() =
        runBlocking {
            assertEquals(listOf(PoolMessages.POOL_NOT_FOUND), service.simulateSwap(swap().copy(poolAddress = "0xnone")).errors)
            assertEquals(PoolMessages.TOKEN_NOT_IN_POOL, service.executeSwap(swap(tokenIn = "BTC")).error)
        }

    @Test
    fun `large swap warns about price impact and slippage`() =
        runBlocking {
            val simulation = service.simulateSwap(swap(amountIn = "100", amountOutMin = "200000"))

            assertTrue(simulation.priceImpact > 5)
            assertEquals(listOf(PoolMessages.HIGH_PRICE_IMPACT, PoolMessages.HIGH_SLIPPAGE), simulation.warnings)
        }

    @Test
    fun `add liquidity follows the pool ratio`() =
        runBlocking {
            val result =
                service.addLiquidity(AddLiquidityParams(ETH_USDC, dec("10"), dec("30000"), dec("9"), dec("19000"), "lp", 0))

            assertTrue(result.success)
            assertEquals(dec("10.000000000000000000"), result.actualAmountA)
            assertEquals(dec("20000.000000"), result.actualAmountB)
            assertEquals(dec("447.213595499957939281"), result.liquidityAmount)
            assertAmount("1010", service.getPool(ETH_USDC)!!.reserveA)
        }

    @Test
    fun `add liquidity below the minimum fails`() =
        runBlocking {
            val result =
                service.addLiquidity(AddLiquidityParams(ETH_USDC, dec("10"), dec("30000"), dec("0"), dec("25000"), "lp", 0))

            assertEquals(PoolMessages.MIN_LIQUIDITY_NOT_MET, result.error)
        }

    @Test
    fun `remove liquidity returns a proportional share`() =
        runBlocking {
            val result = service.removeLiquidity(RemoveLiquidityParams(ETH_USDC, dec("100"), dec("2"), dec("4000"), "lp", 0))

            assertTrue(result.success)
            assertEquals(dec("2.236067977499789696"), result.amountA)
            assertEquals(dec("4472.135954"), result.amountB)
        }

    @Test
    fun `removing all liquidity is rejected so reserves never reach zero`() =
        runBlocking {
            val result = service.removeLiquidity(RemoveLiquidityParams(ETH_USDC, dec("44721.36"), dec("0"), dec("0"), "lp", 0))

            assertEquals(PoolMessages.INSUFFICIENT_POOL_LIQUIDITY, result.error)
            assertAmount("1000", service.getPool(ETH_USDC)!!.reserveA)
        }

    @Test
    fun `search filters by token, sorts and pages`() =
        runBlocking {
            val result =
                service.searchPools(PoolListQuery(token = "USDC", sortBy = PoolSortBy.PRICE, descending = true, limit = 2, offset = 0))

            assertEquals(3, result.total)
            assertEquals(listOf("ETH/USDC", "WETH/USDC"), result.pools.map { it.pair })
            assertEquals(1, result.page)
            assertTrue(result.hasNext)
        }

    @Test
    fun `concurrent swaps are applied one at a time without lost updates`() =
        runBlocking {
            val swaps = 200

            (1..swaps)
                .map { async(CoroutineDispatchers.Default) { service.executeSwap(swap(amountIn = "0.01")) } }
                .awaitAll()

            val pool = service.getPool(ETH_USDC)!!
            assertAmount("1002", pool.reserveA)
            assertAmount("2", pool.totalVolume)
            assertEquals(pool.reserveA * pool.reserveB, pool.k)
        }

    @Test
    fun `missing pool reads return null`() =
        runBlocking {
            assertNull(service.getPool("0xnone"))
            assertNull(service.getPoolStats("0xnone"))
        }

    companion object {
        const val ETH_USDC = "0x8ad599c3A0ff1De082011EFDDc58f1908eb6e6D8"
    }
}
