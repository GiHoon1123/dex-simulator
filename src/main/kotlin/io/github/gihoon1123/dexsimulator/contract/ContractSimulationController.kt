package io.github.gihoon1123.dexsimulator.contract

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

data class SingletonPoolQuery(
    val token: String? = null,
    @DecimalMin("0") val minLiquidity: Double? = null,
    @Pattern(regexp = "liquidity|volume|price|created") val sortBy: String? = null,
    @Pattern(regexp = "asc|desc") val sortOrder: String? = null,
)

data class CreateSingletonPoolRequest(
    @NotBlank val tokenA: String,
    @NotBlank val tokenB: String,
    @DecimalMin("0", inclusive = false) val initialReserveA: BigDecimal,
    @DecimalMin("0", inclusive = false) val initialReserveB: BigDecimal,
    @DecimalMin("0") @DecimalMax("1") val feeRate: BigDecimal,
)

data class SingletonSwapRequest(
    @NotBlank val poolId: String,
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0") val amountIn: BigDecimal,
    @DecimalMin("0") val minAmountOut: BigDecimal = BigDecimal.ZERO,
    val recipient: String = "0x0",
    @DecimalMin("0") val slippageTolerance: Double = 100.0,
) {
    fun toParams() = SingletonSwapParams(poolId, tokenIn, tokenOut, amountIn, minAmountOut, recipient, slippageTolerance)
}

data class RouteOptionsRequest(
    @Min(1) @Max(MAX_HOPS) val maxHops: Int? = null,
    @DecimalMin("0") val slippageTolerance: Double? = null,
    val gasPrice: Long? = null,
    val minPoolLiquidity: Double? = null,
) {
    fun toOptions() = RouteSearchOptions(maxHops, slippageTolerance, gasPrice, minPoolLiquidity)
}

data class AutoSwapRequest(
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0", inclusive = false) val amountIn: BigDecimal,
    @DecimalMin("0") val minAmountOut: BigDecimal? = null,
    @DecimalMin("0") val slippageTolerance: Double = ROUTE_SLIPPAGE,
    val recipient: String = "0x0",
    @Valid val options: RouteOptionsRequest? = null,
)

data class CompareRoutesRequest(
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0", inclusive = false) val amountIn: BigDecimal,
    @Valid val options: RouteOptionsRequest? = null,
)

data class FindRoutesQuery(
    @NotBlank val tokenIn: String,
    @NotBlank val tokenOut: String,
    @DecimalMin("0", inclusive = false) val amountIn: BigDecimal,
    @Min(1) @Max(MAX_HOPS) val maxHops: Int? = null,
    @DecimalMin("0") val slippageTolerance: Double? = null,
)

/** Envelope used by every route of the original controller: `success` plus a payload or `error`. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Envelope(
    val success: Boolean,
    val pool: SingletonPool? = null,
    val simulation: SingletonSwapSimulation? = null,
    val analysis: PriceImpactAnalysis? = null,
    val comparison: RouteComparison? = null,
    val result: RouteSearchResult? = null,
    val poolId: String? = null,
    val tokenIn: String? = null,
    val comparisons: List<ImpactComparison>? = null,
    val message: String? = null,
    val error: String? = null,
)

data class ImpactComparison(
    val amountIn: BigDecimal,
    val priceImpact: Double,
    val level: String,
    val amountOut: BigDecimal,
)

private const val MAX_HOPS = 4L
private const val ROUTE_SLIPPAGE = 0.5

/**
 * Same routes and bodies as the original `ContractSimulationController`. Business failures
 * answer with `success: false` and the error in the body, as in the original.
 */
@Tag(name = "Contract Simulation")
@RestController
@RequestMapping("/contract-simulation")
class ContractSimulationController(
    private val singleton: SingletonContractService,
    private val router: RouterContractService,
) {
    @Operation(summary = "싱글톤 풀 목록 조회")
    @GetMapping("/pools")
    suspend fun getAllPools(
        @Valid @ModelAttribute query: SingletonPoolQuery,
    ): List<SingletonPool> =
        singleton.getAllPools(
            token = query.token,
            minLiquidity = query.minLiquidity,
            sortBy = query.sortBy?.let { sort -> SingletonSortBy.entries.first { it.wire == sort } },
            descending = query.sortOrder == "desc",
        )

    @Operation(summary = "풀 조회")
    @GetMapping("/pools/{poolId}")
    suspend fun getPool(
        @PathVariable poolId: String,
    ): Envelope = poolEnvelope(singleton.getPool(poolId))

    @Operation(summary = "토큰 쌍으로 풀 조회")
    @GetMapping("/pools/find/by-tokens")
    suspend fun findPoolByTokens(
        @RequestParam tokenA: String,
        @RequestParam tokenB: String,
    ): Envelope = poolEnvelope(singleton.findPoolByTokens(tokenA, tokenB))

    @Operation(summary = "풀 생성")
    @PostMapping("/pools")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createPool(
        @Valid @RequestBody request: CreateSingletonPoolRequest,
    ): Envelope =
        guarded {
            val pool =
                singleton.createPool(
                    CreateSingletonPoolParams(
                        request.tokenA,
                        request.tokenB,
                        request.initialReserveA,
                        request.initialReserveB,
                        request.feeRate,
                    ),
                )
            Envelope(success = true, pool = pool)
        }

    @Operation(summary = "풀 통계")
    @GetMapping("/stats")
    suspend fun getPoolStats(): SingletonPoolStats = singleton.getPoolStats()

    @Operation(summary = "스왑 시뮬레이션")
    @PostMapping("/simulate-swap")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun simulateSwap(
        @Valid @RequestBody request: SingletonSwapRequest,
    ): Envelope {
        val simulation = singleton.simulateSwap(request.toParams())
        return Envelope(success = simulation.isExecutable, simulation = simulation)
    }

    @Operation(summary = "스왑 실행")
    @PostMapping("/swap")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun executeSwap(
        @Valid @RequestBody request: SingletonSwapRequest,
    ): SingletonSwapResult = singleton.executeSwap(request.toParams())

    @Operation(summary = "가격 임팩트 분석")
    @GetMapping("/analyze-price-impact")
    suspend fun analyzePriceImpact(
        @RequestParam poolId: String,
        @RequestParam tokenIn: String,
        @RequestParam amountIn: BigDecimal,
    ): Envelope = guarded { Envelope(success = true, analysis = singleton.analyzePriceImpact(poolId, tokenIn, amountIn)) }

    @Operation(summary = "거래량별 가격 임팩트 비교")
    @GetMapping("/compare-price-impacts")
    suspend fun comparePriceImpacts(
        @RequestParam poolId: String,
        @RequestParam tokenIn: String,
        @RequestParam amounts: String,
    ): Envelope =
        guarded {
            val comparisons =
                amounts.split(",").map { raw ->
                    val analysis = singleton.analyzePriceImpact(poolId, tokenIn, BigDecimal(raw.trim()))
                    ImpactComparison(analysis.amountIn, analysis.impact, analysis.level, analysis.amountOut)
                }
            Envelope(success = true, poolId = poolId, tokenIn = tokenIn, comparisons = comparisons)
        }

    @Operation(summary = "모든 풀 초기화")
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun resetPools(): Envelope {
        singleton.resetPools()
        return Envelope(success = true, message = "모든 풀이 초기화되었습니다")
    }

    @Operation(summary = "자동 경로 스왑")
    @PostMapping("/auto-swap")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun autoSwap(
        @Valid @RequestBody request: AutoSwapRequest,
    ): AutoSwapResult =
        router.autoSwap(
            AutoSwapParams(
                tokenIn = request.tokenIn,
                tokenOut = request.tokenOut,
                amountIn = request.amountIn,
                minAmountOut = request.minAmountOut,
                slippageTolerance = request.slippageTolerance,
                recipient = request.recipient,
                options = request.options?.toOptions(),
            ),
        )

    @Operation(summary = "경로 비교")
    @PostMapping("/compare-routes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun compareRoutes(
        @Valid @RequestBody request: CompareRoutesRequest,
    ): Envelope =
        guarded {
            Envelope(
                success = true,
                comparison = router.compareRoutes(request.tokenIn, request.tokenOut, request.amountIn, request.options?.toOptions()),
            )
        }

    @Operation(summary = "경로 탐색")
    @GetMapping("/find-routes")
    suspend fun findRoutes(
        @Valid @ModelAttribute query: FindRoutesQuery,
    ): Envelope =
        guarded {
            val options = RouteSearchOptions(maxHops = query.maxHops ?: 3, slippageTolerance = query.slippageTolerance ?: ROUTE_SLIPPAGE)
            Envelope(success = true, result = router.searchRoutes(query.tokenIn, query.tokenOut, query.amountIn, options))
        }

    private fun poolEnvelope(pool: SingletonPool?) =
        if (pool == null) Envelope(success = false, error = SingletonContractService.NOT_FOUND) else Envelope(success = true, pool = pool)

    /** The original caught business errors and answered `success: false` instead of an error status. */
    private suspend fun guarded(block: suspend () -> Envelope): Envelope =
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Envelope(success = false, error = e.message ?: "Unknown error")
        } catch (e: IllegalStateException) {
            Envelope(success = false, error = e.message ?: "Unknown error")
        }
}
