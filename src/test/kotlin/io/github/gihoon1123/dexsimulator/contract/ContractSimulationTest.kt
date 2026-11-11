package io.github.gihoon1123.dexsimulator.contract

import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.support.MutableClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractSimulationTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val singleton = SingletonContractService(sim, clock).apply { initializeDefaultPools() }
    private val router = RouterContractService(sim, singleton, clock)

    @AfterEach
    fun tearDown() = sim.close()

    private fun dec(value: String) = BigDecimal(value)

    @Test
    fun `ten default pools with order independent ids`() =
        runBlocking {
            assertEquals(10, singleton.getAllPools().size)
            assertEquals("pool_eth_usdc", singleton.findPoolByTokens("USDC", "ETH")?.poolId)
            assertEquals(2000.0, singleton.getPool("pool_eth_usdc")?.currentPrice)
            assertEquals(
                "풀이 이미 존재합니다: pool_eth_usdc",
                assertThrows<IllegalStateException> {
                    singleton.createPool(CreateSingletonPoolParams("USDC", "ETH", dec("1"), dec("1"), dec("0.003")))
                }.message,
            )
        }

    @Test
    fun `swap keeps the whole input in the pool and updates k, price and volume`() =
        runBlocking {
            val result = singleton.executeSwap(SingletonSwapParams("pool_eth_usdc", "ETH", "USDC", dec("10")))

            assertTrue(result.success)
            val pool = assertNotNull(singleton.getPool("pool_eth_usdc"))
            assertEquals(0, dec("10010").compareTo(pool.reserveA))
            assertEquals(0, (dec("20000000") - result.amountOut).compareTo(pool.reserveB))
            assertEquals(0, (pool.reserveA * pool.reserveB).compareTo(pool.k))
            assertEquals(1, pool.swapCount)
            assertEquals(0, dec("0.03").compareTo(result.fee))
            assertEquals(20000.0, singleton.getPoolStats().totalVolume)
        }

    @Test
    fun `simulation reports slippage, minimum and liquidity errors with original messages`() =
        runBlocking {
            val sim =
                singleton.simulateSwap(
                    SingletonSwapParams("pool_dai_eth", "ETH", "DAI", dec("100"), minAmountOut = dec("999999"), slippageTolerance = 0.5),
                )

            assertFalse(sim.isExecutable)
            assertTrue(sim.warnings.single().startsWith("높은 가격 임팩트: "))
            assertTrue(sim.errors[0].startsWith("슬리피지 초과: ") && sim.errors[0].endsWith("% > 0.5%"))
            assertTrue(sim.errors[1].startsWith("최소 출력량 미달: ") && sim.errors[1].endsWith(" < 999999"))
            assertEquals(listOf("잘못된 토큰 쌍입니다"), singleton.simulateSwap(SingletonSwapParams("pool_dai_eth", "BTC", "DAI", dec("1"))).errors)

            val drain = singleton.simulateSwap(SingletonSwapParams("pool_btc_dai", "DAI", "BTC", dec("100000000")))
            assertTrue("출력량이 풀 유동성의 90%를 초과합니다" in drain.errors)
        }

    @Test
    fun `price impact analysis levels`() =
        runBlocking {
            assertEquals("low", singleton.analyzePriceImpact("pool_eth_usdc", "ETH", dec("1")).level)
            assertEquals("extreme", singleton.analyzePriceImpact("pool_dai_eth", "ETH", dec("100")).level)
            assertThrows<IllegalArgumentException> { singleton.analyzePriceImpact("pool_none", "ETH", dec("1")) }
            Unit
        }

    @Test
    fun `path search finds direct and multi hop routes without revisiting tokens`() =
        runBlocking {
            val result = router.searchRoutes("ETH", "DAI", dec("1"))

            assertEquals(1, result.directRoutes)
            assertTrue(result.twoHopRoutes > 0 && result.threeHopRoutes > 0)
            result.routes.forEach { route ->
                assertEquals(route.path.size, route.path.toSet().size)
                assertEquals(RouterContractService.BASE_GAS + route.hops * RouterContractService.GAS_PER_HOP, route.gasEstimate)
            }
        }

    @Test
    fun `small pool loses to a multi hop route`() =
        runBlocking {
            // ETH/DAI has only 500 ETH; going through ETH/USDC and USDC/DAI gives more DAI.
            val comparison = router.compareRoutes("ETH", "DAI", dec("50"), RouteSearchOptions(slippageTolerance = 100.0))

            assertEquals(RouteType.MULTI_HOP, comparison.bestRoute.type)
            assertTrue(comparison.bestRoute.recommended)
            assertEquals(RouteType.MULTI_HOP, comparison.comparison?.recommendation)
            assertTrue(comparison.singletonAdvantage.gasSaved > 0)
        }

    @Test
    fun `auto swap executes every hop of the best route`() =
        runBlocking {
            val result =
                router.autoSwap(
                    AutoSwapParams("ETH", "DAI", dec("50"), null, 100.0, "me", RouteSearchOptions(slippageTolerance = 100.0)),
                )

            assertTrue(result.success)
            val route = assertNotNull(result.routeUsed)
            assertEquals(route.hops, result.hopsExecuted.size)
            assertEquals(0, result.finalAmountOut.compareTo(result.hopsExecuted.last().amountOut))
            result.hopsExecuted.zipWithNext { a, b -> assertEquals(0, a.amountOut.compareTo(b.amountIn)) }
        }

    @Test
    fun `auto swap below the minimum output changes no pool`() =
        runBlocking {
            val before = singleton.getAllPools()

            val result =
                router.autoSwap(
                    AutoSwapParams("ETH", "DAI", dec("50"), dec("999999999"), 100.0, "me", RouteSearchOptions(slippageTolerance = 100.0)),
                )

            assertFalse(result.success)
            assertTrue(result.error!!.startsWith("최소 출력량 미달: "))
            assertTrue(result.hopsExecuted.isEmpty())
            // The original had already applied every hop before reporting this failure.
            assertEquals(before, singleton.getAllPools())
        }

    @Test
    fun `no route and infeasible routes are reported without changes`() =
        runBlocking {
            val none = router.autoSwap(AutoSwapParams("ETH", "XYZ", dec("1"), null, 0.5, "me"))
            assertEquals("가능한 경로를 찾을 수 없습니다", none.error)
            assertNull(none.routeUsed)

            val before = singleton.getAllPools()
            val huge = router.autoSwap(AutoSwapParams("BTC", "DAI", dec("100000"), null, 0.5, "me", RouteSearchOptions(maxHops = 1)))
            assertFalse(huge.success)
            assertEquals(before, singleton.getAllPools())
        }

    @Test
    fun `reset restores the default pools`() =
        runBlocking {
            singleton.executeSwap(SingletonSwapParams("pool_eth_usdc", "ETH", "USDC", dec("10")))
            singleton.resetPools()

            assertEquals(0, singleton.getPoolStats().totalSwaps)
            assertEquals(0.0, singleton.getPoolStats().totalVolume)
        }
}
