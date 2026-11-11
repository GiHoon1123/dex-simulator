package io.github.gihoon1123.dexsimulator.dex

import io.github.gihoon1123.dexsimulator.common.error.ApiException
import io.github.gihoon1123.dexsimulator.dex.lp.LpService
import io.github.gihoon1123.dexsimulator.dex.market.MarketService
import io.github.gihoon1123.dexsimulator.dex.trader.TraderService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class MessageOnly(
    val message: String,
)

@Tag(name = "Market")
@RestController
@RequestMapping("/market")
class MarketController(
    private val marketService: MarketService,
    private val lpService: LpService,
) {
    @Operation(summary = "현재 시장 가격 조회")
    @GetMapping("/price")
    suspend fun getCurrentPrice(): MarketPrice = marketService.getCurrentPrice()

    @Operation(summary = "시장 가격 변동 시뮬레이션")
    @PostMapping("/simulate-price-change")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun simulatePriceChange(): PriceChangeEvent = marketService.simulatePriceChange()

    @Operation(summary = "시장 상태 조회")
    @GetMapping("/status")
    suspend fun getMarketStatus(): MarketStatus = marketService.getMarketStatus()

    /** The original compared against a hard-coded 1000 ETH / 30000 BTC; this uses the LP pool. */
    @Operation(summary = "아비트라지 기회 체크")
    @PostMapping("/check-arbitrage")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun checkArbitrageOpportunity(): MessageOnly {
        if (!lpService.isInitialized()) throw ApiException(HttpStatus.BAD_REQUEST, LpService.NOT_INITIALIZED)
        val pool = lpService.getPool()
        marketService.checkAndEmitArbitrageOpportunity(pool.eth, pool.btc)
        return MessageOnly("아비트라지 기회 체크 완료")
    }
}

@Tag(name = "LP")
@RestController
@RequestMapping("/lp")
class LpController(
    private val lpService: LpService,
) {
    @Operation(summary = "풀 초기화")
    @PostMapping("/init")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun initLiquidity(): LpPool = lpService.initLiquidity()

    @Operation(summary = "풀 상태 조회")
    @GetMapping("/status")
    suspend fun getPool(): LpPool = lpService.getPool()

    @Operation(summary = "유저 추가")
    @PostMapping("/add-user")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun addRandomUser(): LpPool = lpService.addRandomUser()

    @Operation(summary = "유저 제거")
    @DeleteMapping("/remove-user")
    suspend fun removeRandomUser(): LpPool = lpService.removeRandomUser()
}

@Tag(name = "Trader")
@RestController
@RequestMapping("/trader")
class TraderController(
    private val traderService: TraderService,
) {
    @Operation(summary = "랜덤 거래 실행")
    @PostMapping("/execute-random-trade")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun executeRandomTrade(): TradeResult = traderService.executeRandomTrade()

    @Operation(summary = "아비트라지 거래 실행")
    @PostMapping("/execute-arbitrage")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun executeArbitrage(): ArbitrageCheckResult = traderService.checkAndExecuteArbitrage()
}
