package io.github.gihoon1123.dexsimulator.dex

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.dex.lp.LpService
import io.github.gihoon1123.dexsimulator.dex.market.MarketService
import io.github.gihoon1123.dexsimulator.dex.trader.TraderService
import io.github.gihoon1123.dexsimulator.support.EventRecorder
import io.github.gihoon1123.dexsimulator.support.MutableClock
import io.github.gihoon1123.dexsimulator.support.ScriptedRandom
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.MathContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers as CoroutineDispatchers

/** Ported from the original `lp.service.spec.ts` and `trader.service.spec.ts`; repeated coverage-only cases were merged. */
class LpAndTraderServiceTest {
    private val clock = MutableClock()
    private val sim = SimulationContext()
    private val bus = EventBus()
    private val random = ScriptedRandom()
    private val lp = LpService(sim, bus, random, clock)
    private val market = MarketService(sim, bus, random, clock)
    private val trader = TraderService(sim, bus, lp, market, random, clock)

    @AfterEach
    fun tearDown() = sim.close()

    private fun dec(value: String) = BigDecimal(value)

    private fun assertAmount(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, dec(expected).compareTo(actual), "expected $expected but was ${actual.toPlainString()}")

    // ---- LP ----

    @Test
    fun `uninitialized pool rejects every operation`() =
        runBlocking {
            listOf<suspend () -> Unit>(
                { lp.getPool() },
                { lp.addRandomUser() },
                { lp.removeRandomUser() },
                { lp.calculateDynamicFee() },
                { trader.executeRandomTrade() },
            ).forEach { call ->
                val error = assertThrows<ApiException> { call() }
                assertEquals(LpService.NOT_INITIALIZED, error.message)
            }
        }

    @Test
    fun `init creates 10 users holding 1000 ETH and 30000 BTC at 1 to 30`() =
        runBlocking {
            val pool = lp.initLiquidity()

            assertEquals(10, pool.userCount)
            assertAmount("1000", pool.eth)
            assertAmount("30000", pool.btc)
            assertAmount("30000000", pool.k)
            assertEquals(LpService.BASE_FEE_RATE, pool.feeRate)
            assertEquals(2000.0, pool.initialPoolValue)
            assertEquals(1.0, pool.users.sumOf { it.share }, 0.02)
            pool.users.filter { it.eth.signum() > 0 }.forEach { user ->
                assertEquals(30.0, user.btc.toDouble() / user.eth.toDouble(), 0.1)
                assertAmount(
                    (user.share * 100).toBigDecimal().setScale(2, java.math.RoundingMode.DOWN).toPlainString(),
                    user.governanceTokens,
                )
            }
        }

    @Test
    fun `adding users grows the pool, recalculates k and shares, and stops at 30`() =
        runBlocking {
            lp.initLiquidity()
            val grown = lp.addRandomUser()

            assertTrue(grown.userCount > 10)
            assertTrue(grown.eth > dec("1000") && grown.btc > dec("30000"))
            assertEquals(0, (grown.eth * grown.btc).compareTo(grown.k))
            assertEquals(1.0, grown.users.sumOf { it.share }, 0.05)

            while (lp.getPool().userCount < LpService.MAX_USERS) lp.addRandomUser()
            assertEquals("최대 유저 수(30명)에 도달했습니다.", assertThrows<ApiException> { lp.addRandomUser() }.message)
        }

    @Test
    fun `removing users takes their deposits out and stops at 10`() =
        runBlocking {
            lp.initLiquidity()
            assertEquals("최소 유저 수(10명)에 도달했습니다.", assertThrows<ApiException> { lp.removeRandomUser() }.message)

            lp.addRandomUser()
            val before = lp.getPool()
            val after = lp.removeRandomUser()

            val removed = before.users.filter { user -> after.users.none { it.id == user.id } }
            assertTrue(removed.isNotEmpty())
            assertAmount((before.eth - removed.sumOf { it.eth }).toPlainString(), after.eth)
            assertAmount((before.btc - removed.sumOf { it.btc }).toPlainString(), after.btc)
            assertEquals(0, (after.eth * after.btc).compareTo(after.k))
        }

    @Test
    fun `dynamic fee follows pool size within bounds`() =
        runBlocking {
            lp.initLiquidity()
            assertEquals(LpService.BASE_FEE_RATE, lp.calculateDynamicFee())

            while (lp.getPool().userCount < LpService.MAX_USERS) lp.addRandomUser()

            val grownFee = lp.calculateDynamicFee()
            assertTrue(grownFee < LpService.BASE_FEE_RATE && grownFee >= LpService.MIN_FEE_RATE)
        }

    @Test
    fun `fee is amount times the dynamic rate truncated to the token scale`() =
        runBlocking {
            lp.initLiquidity()

            assertAmount("0.3", lp.calculateFee(dec("100"), 18))
            assertAmount("0.00000003", lp.calculateFee(dec("0.0000100"), 8))
            assertAmount("0", lp.calculateFee(dec("0.000001"), 8))
        }

    @Test
    fun `price change folds volatility into the fee and is ignored before init`() =
        runBlocking {
            val event = market.simulatePriceChange()
            lp.handleMarketPriceChange(event) // no pool yet: ignored instead of throwing

            lp.initLiquidity()
            random.push(1.0, 0.0)
            val big = market.simulatePriceChange()
            lp.handleMarketPriceChange(big)

            val pool = lp.getPool()
            assertEquals(kotlin.math.abs(big.change.eth), pool.volatility.eth)
            assertEquals(big.volatility, pool.volatility.overall)
            val expected = LpService.BASE_FEE_RATE * (1.0 * 0.6 + (1 + big.volatility / 100 * 2) * 0.4)
            assertEquals(expected.coerceIn(LpService.MIN_FEE_RATE, LpService.MAX_FEE_RATE), pool.feeRate, 1e-12)
        }

    // ---- Trader ----

    @Test
    fun `eth to btc trade follows the constant product and pays the fee to LPs`() =
        runBlocking {
            lp.initLiquidity()

            val result = trader.executeTrade(Asset.ETH, 0.02)

            val trade = result.trade
            assertEquals("trade_1", trade.id)
            assertAmount("20", trade.amountIn)
            assertAmount("0.06", trade.fee)
            // out = 30000 - 30000000 / (1000 + 19.94), truncated to BTC's 8 decimals.
            val exact = dec("30000") - dec("30000000").divide(dec("1019.94"), MathContext.DECIMAL128)
            assertAmount(exact.setScale(8, java.math.RoundingMode.DOWN).toPlainString(), trade.amountOut)
            assertAmount("1019.94", result.poolAfter.eth)
            assertAmount((dec("30000") - trade.amountOut).toPlainString(), result.poolAfter.btc)
            assertAmount("30000000", result.poolAfter.k)
            assertEquals(30.0, result.priceInfo.expectedRate)
            assertEquals(1.994, trade.priceImpact)

            val pool = lp.getPool()
            assertAmount("1019.94", pool.eth)
            val paid = pool.users.sumOf { it.earnedEth }
            assertTrue(paid <= trade.fee && paid > dec("0.059"))
            assertTrue(pool.users.all { it.earnedBtc.signum() == 0 })
        }

    @Test
    fun `btc to eth trade moves reserves the other way`() =
        runBlocking {
            lp.initLiquidity()

            val result = trader.executeTrade(Asset.BTC, 0.01)

            assertEquals(Asset.ETH, result.trade.to)
            assertAmount("300", result.trade.amountIn)
            assertTrue(result.poolAfter.eth < dec("1000"))
            assertTrue(lp.getPool().users.any { it.earnedBtc.signum() > 0 })
        }

    @Test
    fun `random trade is 1 to 6 percent and publishes trade executed`() =
        runBlocking {
            lp.initLiquidity()
            val recorder = EventRecorder(bus).start()
            random.push(0.9, 1.0) // ETH, ratio 6%

            val result = trader.executeRandomTrade()

            assertEquals(Asset.ETH, result.trade.from)
            assertAmount("60", result.trade.amountIn)
            recorder.awaitUntil { events -> events.any { it is TradeExecutedEvent } }
            assertEquals(result.trade.id, recorder.ofType<TradeExecutedEvent>().single().tradeId)
            recorder.stop()
        }

    @Test
    fun `random and arbitrage trades share one counter`() =
        runBlocking {
            lp.initLiquidity()
            val opportunity = assertNotNullOpportunity()

            assertEquals("trade_1", trader.executeRandomTrade().trade.id)
            assertEquals("arbitrage_2", trader.handleArbitrageOpportunity(opportunity).trade.id)
            assertEquals("trade_3", trader.executeRandomTrade().trade.id)
        }

    @Test
    fun `no arbitrage below a 5 percent gap`() =
        runBlocking {
            lp.initLiquidity()

            val result = trader.checkAndExecuteArbitrage()

            assertEquals("아비트라지 기회가 없습니다. 현재 차이: 0.00% (최소 5% 필요)", result.message)
        }

    @Test
    fun `arbitrage trade moves the pool price toward the market`() =
        runBlocking {
            lp.initLiquidity()
            // Push ETH up 5% twice in the market: reference btc/eth drops to about 27.2.
            random.push(1.0, 0.5, 1.0, 0.5)
            repeat(2) { market.simulatePriceChange() }
            val gapBefore = gap()

            val result = trader.checkAndExecuteArbitrage()

            assertTrue(result.message.startsWith("아비트라지 거래 실행 완료! 차이: "))
            assertEquals(ArbitrageDirection.BUY_BTC_SELL_ETH, result.opportunity?.direction)
            assertEquals(Asset.ETH, result.trade?.trade?.from)
            assertTrue(result.opportunity!!.opportunityId.startsWith("manual_arbitrage_"))
            // The original traded the other way and widened the gap.
            assertTrue(gap() < gapBefore, "gap ${gap()} should be below $gapBefore")
        }

    @Test
    fun `arbitrage size is between 1 and 3 percent of the reserve`() =
        runBlocking {
            lp.initLiquidity()
            val small = assertNotNullOpportunity().copy(percentage = 5.0)
            val large = small.copy(percentage = 90.0)

            assertAmount("300", trader.handleArbitrageOpportunity(small).trade.amountIn)
            val pool = lp.getPool()
            assertAmount(
                (pool.btc * dec("0.03")).setScale(8, java.math.RoundingMode.DOWN).toPlainString(),
                trader.handleArbitrageOpportunity(large).trade.amountIn,
            )
        }

    @Test
    fun `arbitrage events from the market are traded once`() =
        runBlocking {
            lp.initLiquidity()
            trader.subscribe()
            val recorder = EventRecorder(bus).start()
            random.push(1.0, 0.5, 1.0, 0.5)
            repeat(2) { market.simulatePriceChange() }
            val pool = lp.getPool()

            market.checkAndEmitArbitrageOpportunity(pool.eth, pool.btc)

            recorder.awaitUntil { events -> events.any { it is TradeExecutedEvent } }
            delay(50)
            market.getCurrentPrice()
            // The original published the opportunity twice and traded twice.
            assertEquals(1, recorder.ofType<TradeExecutedEvent>().size)
            assertTrue(
                recorder
                    .ofType<TradeExecutedEvent>()
                    .single()
                    .tradeId
                    .startsWith("arbitrage_"),
            )
            recorder.stop()
        }

    @Test
    fun `concurrent trades are applied one at a time`() =
        runBlocking {
            lp.initLiquidity()

            val results = (1..50).map { async(CoroutineDispatchers.Default) { trader.executeTrade(Asset.ETH, 0.01) } }.awaitAll()

            // Each trade starts from the pool the previous one left, so nothing is lost.
            val ordered =
                results.sortedBy {
                    it.trade.id
                        .removePrefix("trade_")
                        .toInt()
                }
            ordered.zipWithNext { a, b -> assertEquals(0, a.poolAfter.eth.compareTo(b.poolBefore.eth)) }
            assertEquals(
                0,
                ordered
                    .last()
                    .poolAfter.eth
                    .compareTo(lp.getPool().eth),
            )
        }

    private suspend fun gap(): Double {
        val pool = lp.getPool()
        val price = market.getCurrentPrice()
        return kotlin.math.abs(pool.btc.toDouble() / pool.eth.toDouble() - price.btc / price.eth)
    }

    private suspend fun assertNotNullOpportunity(): ArbitrageOpportunity =
        market.calculateArbitrageOpportunity(dec("1000"), dec("20000")) ?: error("expected an opportunity")
}
