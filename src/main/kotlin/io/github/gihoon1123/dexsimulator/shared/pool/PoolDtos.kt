package io.github.gihoon1123.dexsimulator.shared.pool

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant

// Request DTOs with the original field names. The original declared class-validator rules
// but never installed a ValidationPipe, so nothing was validated. Here they are enforced
// (docs/decisions.md #9). Reserves must be positive instead of non-negative, because a
// zero reserve has no price.

data class CreatePoolRequest(
    @NotBlank val address: String,
    @NotBlank val pair: String,
    @NotBlank val tokenA: String,
    @NotBlank val tokenB: String,
    @DecimalMin("0", inclusive = false) val initialReserveA: BigDecimal,
    @DecimalMin("0", inclusive = false) val initialReserveB: BigDecimal,
    @DecimalMin("0") @DecimalMax("1") val feeRate: BigDecimal,
) {
    fun toParams() = CreatePoolParams(address, pair, tokenA, tokenB, initialReserveA, initialReserveB, feeRate)
}

data class ExecuteSwapRequest(
    @NotBlank val poolAddress: String,
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0") val amountIn: BigDecimal,
    @DecimalMin("0") val amountOutMin: BigDecimal,
    @NotBlank val recipient: String,
    val deadline: Long,
    @Min(1) val gasPrice: Long? = null,
    @Min(21000) val gasLimit: Long? = null,
) {
    fun toParams() =
        SwapParams(
            poolAddress = poolAddress,
            tokenIn = tokenIn,
            tokenOut = tokenOut,
            amountIn = amountIn,
            amountOutMin = amountOutMin,
            recipient = recipient,
            deadline = deadline,
            gasPrice = gasPrice ?: DEFAULT_GAS_PRICE,
            gasLimit = gasLimit ?: DEFAULT_GAS_LIMIT,
        )

    companion object {
        const val DEFAULT_GAS_PRICE = 100L
        const val DEFAULT_GAS_LIMIT = 200_000L
    }
}

data class SimulateSwapRequest(
    @NotBlank val poolAddress: String,
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0") val amountIn: BigDecimal,
) {
    /** Same fixed values as the original controller: no minimum, deadline in 5 minutes. */
    fun toParams(nowEpochSeconds: Long) =
        SwapParams(
            poolAddress = poolAddress,
            tokenIn = tokenIn,
            tokenOut = tokenOut,
            amountIn = amountIn,
            amountOutMin = BigDecimal.ZERO,
            recipient = "simulation",
            deadline = nowEpochSeconds + SIMULATION_DEADLINE_SECONDS,
            gasPrice = ExecuteSwapRequest.DEFAULT_GAS_PRICE,
            gasLimit = ExecuteSwapRequest.DEFAULT_GAS_LIMIT,
        )

    companion object {
        const val SIMULATION_DEADLINE_SECONDS = 300L
    }
}

data class AddLiquidityRequest(
    @NotBlank val poolAddress: String,
    @DecimalMin("0") val amountA: BigDecimal,
    @DecimalMin("0") val amountB: BigDecimal,
    @DecimalMin("0") val amountAMin: BigDecimal,
    @DecimalMin("0") val amountBMin: BigDecimal,
    @NotBlank val recipient: String,
    val deadline: Long,
) {
    fun toParams() = AddLiquidityParams(poolAddress, amountA, amountB, amountAMin, amountBMin, recipient, deadline)
}

data class RemoveLiquidityRequest(
    @NotBlank val poolAddress: String,
    @DecimalMin("0") val liquidityAmount: BigDecimal,
    @DecimalMin("0") val amountAMin: BigDecimal,
    @DecimalMin("0") val amountBMin: BigDecimal,
    @NotBlank val recipient: String,
    val deadline: Long,
) {
    fun toParams() = RemoveLiquidityParams(poolAddress, liquidityAmount, amountAMin, amountBMin, recipient, deadline)
}

data class GetPoolsQuery(
    val activeOnly: Boolean? = null,
    val token: String? = null,
    @Pattern(regexp = "volume|liquidity|price|created") val sortBy: String? = null,
    @Pattern(regexp = "asc|desc") val sortOrder: String? = null,
    @Min(1) @Max(100) val limit: Int? = null,
    @Min(0) val offset: Int? = null,
) {
    // As in the original: no sorting without sortBy, ascending unless sortOrder is "desc".
    fun toQuery() =
        PoolListQuery(
            activeOnly = activeOnly ?: false,
            token = token,
            sortBy = sortBy?.let(PoolSortBy::of),
            descending = sortOrder == "desc",
            limit = limit ?: DEFAULT_LIMIT,
            offset = offset ?: 0,
        )

    companion object {
        const val DEFAULT_LIMIT = 10
    }
}

data class UpdateOracleConfigRequest(
    @Min(60) @Max(3600) val twapPeriod: Int? = null,
    @Min(10) @Max(300) val updateInterval: Int? = null,
    @DecimalMin("0.1") @DecimalMax("10") val mevThreshold: Double? = null,
    @DecimalMin("0.1") @DecimalMax("1") val confidenceThreshold: Double? = null,
    @DecimalMin("1") @DecimalMax("50") val maxDeviation: Double? = null,
    val autoExecute: Boolean? = null,
) {
    fun applyTo(config: OracleConfig) =
        config.copy(
            twapPeriod = twapPeriod ?: config.twapPeriod,
            updateInterval = updateInterval ?: config.updateInterval,
            mevThreshold = mevThreshold ?: config.mevThreshold,
            confidenceThreshold = confidenceThreshold ?: config.confidenceThreshold,
            maxDeviation = maxDeviation ?: config.maxDeviation,
            autoExecute = autoExecute ?: config.autoExecute,
        )
}

data class GetMevOpportunitiesQuery(
    val poolAddress: String? = null,
    @Pattern(regexp = "front_run|back_run|sandwich|arbitrage") val strategy: String? = null,
    @DecimalMin("0") val minProfit: Double? = null,
    @Min(1) @Max(5) val maxRisk: Int? = null,
    val executableOnly: Boolean? = null,
) {
    fun toFilter() =
        MevOpportunityFilter(
            poolAddress = poolAddress,
            strategy = strategy?.let(MevStrategy::of),
            minProfit = minProfit,
            maxRisk = maxRisk,
            executableOnly = executableOnly ?: false,
        )
}

data class GetTwapQuery(
    @NotBlank val poolAddress: String,
    @Min(60) @Max(3600) val period: Int? = null,
)

data class MessageResponse(
    val message: String,
)

data class SystemResponse(
    val message: String,
    val status: String,
)

data class PoolCounts(
    val total: Int,
    val active: Int,
)

data class SystemHealthResponse(
    val pools: PoolCounts,
    val oracle: OracleStatus,
    val lastUpdate: Instant,
)
