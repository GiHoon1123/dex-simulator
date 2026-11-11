package io.github.gihoon1123.dexsimulator.dex

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import io.github.gihoon1123.dexsimulator.common.event.DomainEvent
import java.math.BigDecimal
import java.time.Instant

// Models of the ETH/BTC educational pool (market, lp, trader), with the original field names.
// Token amounts are BigDecimal; prices, rates and percentages are Double (decisions #3).

enum class Asset(
    @get:JsonValue val symbol: String,
) {
    ETH("ETH"),
    BTC("BTC"),
    ;

    val other: Asset get() = if (this == ETH) BTC else ETH
}

/** USD prices. `ratio` is eth / btc. */
data class MarketPrice(
    val eth: Double,
    val btc: Double,
    val ratio: Double,
    val timestamp: Instant,
)

data class PriceChange(
    val eth: Double,
    val btc: Double,
)

/** Returned by the API and published as `market.price.changed`. */
data class PriceChangeEvent(
    override val eventId: String,
    override val timestamp: Instant,
    val previousPrice: MarketPrice,
    val currentPrice: MarketPrice,
    /** Percent. */
    val change: PriceChange,
    /** Percent. */
    val volatility: Double,
) : DomainEvent {
    @get:JsonIgnore
    override val type: String get() = DexEventTypes.MARKET_PRICE_CHANGED
}

enum class ArbitrageDirection(
    @get:JsonValue val wire: String,
) {
    /** ETH is cheaper in the pool than in the market: pay BTC, take ETH. */
    BUY_ETH_SELL_BTC("buy_eth_sell_btc"),

    /** ETH is dearer in the pool than in the market: pay ETH, take BTC. */
    BUY_BTC_SELL_ETH("buy_btc_sell_eth"),
}

data class ArbitrageOpportunity(
    val opportunityId: String,
    val timestamp: Instant,
    /** ETH price in the pool, btc / eth reserves. */
    val poolPrice: Double,
    /** Reference from market USD prices, btc / eth (see decisions #11). */
    val marketPrice: Double,
    val difference: Double,
    val percentage: Double,
    val direction: ArbitrageDirection,
)

data class VolatilityMetrics(
    val eth: Double,
    val btc: Double,
    val overall: Double,
)

data class MarketStatus(
    val currentPrice: MarketPrice,
    val volatility: VolatilityMetrics,
    val arbitrageOpportunity: ArbitrageOpportunity?,
    val lastUpdate: Instant,
)

data class LpUser(
    val id: Int,
    val eth: BigDecimal,
    val btc: BigDecimal,
    val share: Double,
    val earnedEth: BigDecimal,
    val earnedBtc: BigDecimal,
    val governanceTokens: BigDecimal,
)

data class LpPool(
    val eth: BigDecimal,
    val btc: BigDecimal,
    val k: BigDecimal,
    val feeRate: Double,
    val userCount: Int,
    val users: List<LpUser>,
    /** Value in ETH. */
    val initialPoolValue: Double,
    val currentPoolValue: Double,
    val poolSizeRatio: Double,
    val volatility: VolatilityMetrics,
    val lastVolatilityUpdate: Instant,
)

data class PoolSnapshot(
    val eth: BigDecimal,
    val btc: BigDecimal,
    val k: BigDecimal,
)

data class Trade(
    val id: String,
    val from: Asset,
    val to: Asset,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val fee: BigDecimal,
    val slippage: Double,
    val priceImpact: Double,
    val timestamp: Instant,
)

data class PriceInfo(
    val expectedRate: Double,
    val actualRate: Double,
    val slippage: Double,
)

data class TradeResult(
    val trade: Trade,
    val poolBefore: PoolSnapshot,
    val poolAfter: PoolSnapshot,
    val priceInfo: PriceInfo,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ArbitrageCheckResult(
    val message: String,
    val opportunity: ArbitrageOpportunity? = null,
    val trade: TradeResult? = null,
)

object DexEventTypes {
    const val TRADE_EXECUTED = "trade.executed"
    const val MARKET_PRICE_CHANGED = "market.price.changed"
    const val ARBITRAGE_OPPORTUNITY = "arbitrage.opportunity"
}

data class TradeExecutedEvent(
    override val eventId: String,
    val tradeId: String,
    val from: Asset,
    val to: Asset,
    val amountIn: BigDecimal,
    val amountOut: BigDecimal,
    val fee: BigDecimal,
    val slippage: Double,
    val priceImpact: Double,
    val poolBefore: PoolSnapshot,
    val poolAfter: PoolSnapshot,
    override val timestamp: Instant,
) : DomainEvent {
    override val type: String get() = DexEventTypes.TRADE_EXECUTED
}

data class ArbitrageOpportunityEvent(
    override val eventId: String,
    val opportunity: ArbitrageOpportunity,
    override val timestamp: Instant,
) : DomainEvent {
    override val type: String get() = DexEventTypes.ARBITRAGE_OPPORTUNITY
}
