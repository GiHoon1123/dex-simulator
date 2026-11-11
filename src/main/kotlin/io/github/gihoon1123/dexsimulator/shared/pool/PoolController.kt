package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * Same routes, bodies and status codes as the original `PoolController`
 * (POST answers 201 like NestJS, a missing resource answers 200 with an empty body).
 */
@Tag(name = "Pool")
@RestController
@RequestMapping("/pool")
class PoolController(
    private val poolService: PoolService,
    private val oracleService: PoolOracleService,
    private val clock: Clock,
) {
    @Operation(summary = "새 풀 생성")
    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createPool(
        @Valid @RequestBody request: CreatePoolRequest,
    ): PoolState = poolService.createPool(request.toParams())

    @Operation(summary = "풀 목록 조회")
    @GetMapping("/list")
    suspend fun getPools(
        @Valid @ModelAttribute query: GetPoolsQuery,
    ): PoolSearchResult = poolService.searchPools(query.toQuery())

    @Operation(summary = "풀 상세 조회")
    @GetMapping("/{poolAddress}")
    suspend fun getPool(
        @PathVariable poolAddress: String,
    ): PoolState? = poolService.getPool(poolAddress)

    @Operation(summary = "풀 통계 조회")
    @GetMapping("/{poolAddress}/stats")
    suspend fun getPoolStats(
        @PathVariable poolAddress: String,
    ): PoolStats? = poolService.getPoolStats(poolAddress)

    @Operation(summary = "전체 풀 통계 조회")
    @GetMapping("/stats/all")
    suspend fun getAllPoolStats(): List<PoolStats> = poolService.getAllPoolStats()

    @Operation(summary = "스왑 시뮬레이션")
    @PostMapping("/swap/simulate")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun simulateSwap(
        @Valid @RequestBody request: SimulateSwapRequest,
    ): SwapSimulation = poolService.simulateSwap(request.toParams(clock.nowMillis().epochSecond))

    @Operation(summary = "스왑 실행")
    @PostMapping("/swap/execute")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun executeSwap(
        @Valid @RequestBody request: ExecuteSwapRequest,
    ): SwapResult = poolService.executeSwap(request.toParams())

    @Operation(summary = "유동성 추가")
    @PostMapping("/liquidity/add")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun addLiquidity(
        @Valid @RequestBody request: AddLiquidityRequest,
    ): AddLiquidityResult = poolService.addLiquidity(request.toParams())

    @Operation(summary = "유동성 제거")
    @PostMapping("/liquidity/remove")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun removeLiquidity(
        @Valid @RequestBody request: RemoveLiquidityRequest,
    ): RemoveLiquidityResult = poolService.removeLiquidity(request.toParams())

    @Operation(summary = "오라클 상태 조회")
    @GetMapping("/oracle/status")
    suspend fun getOracleStatus(): OracleStatus = oracleService.getOracleStatus()

    @Operation(summary = "전체 풀 가격 조회")
    @GetMapping("/oracle/prices")
    suspend fun getAllPoolPrices(): List<PoolPrice> = oracleService.getAllPoolPrices()

    @Operation(summary = "풀 가격 조회")
    @GetMapping("/oracle/price/{poolAddress}")
    suspend fun getPoolPrice(
        @PathVariable poolAddress: String,
    ): PoolPrice? = oracleService.getPoolPrice(poolAddress)

    @Operation(summary = "TWAP 조회")
    @GetMapping("/oracle/twap")
    suspend fun getTwap(
        @Valid @ModelAttribute query: GetTwapQuery,
    ): TwapInfo? = oracleService.calculateTwap(query.poolAddress, query.period)

    @Operation(summary = "오라클 설정 업데이트")
    @PostMapping("/oracle/config")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun updateOracleConfig(
        @Valid @RequestBody request: UpdateOracleConfigRequest,
    ): MessageResponse {
        oracleService.updateConfig(request::applyTo)
        return MessageResponse("오라클 설정이 업데이트되었습니다")
    }

    @Operation(summary = "MEV 기회 목록 조회")
    @GetMapping("/mev/opportunities")
    suspend fun getMevOpportunities(
        @Valid @ModelAttribute query: GetMevOpportunitiesQuery,
    ): List<MevOpportunity> = oracleService.getActiveOpportunities(query.toFilter())

    @Operation(summary = "MEV 기회 상세 조회")
    @GetMapping("/mev/opportunities/{opportunityId}")
    suspend fun getMevOpportunity(
        @PathVariable opportunityId: String,
    ): MevOpportunity? = oracleService.getOpportunity(opportunityId)

    /** The original returned a message only; here it also restarts a stopped oracle. */
    @Operation(summary = "풀 시스템 시작")
    @PostMapping("/system/start")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun startPoolSystem(): SystemResponse {
        oracleService.start()
        return SystemResponse("풀 시스템이 시작되었습니다", "running")
    }

    @Operation(summary = "풀 시스템 중지")
    @PostMapping("/system/stop")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun stopPoolSystem(): SystemResponse {
        oracleService.stop()
        return SystemResponse("풀 시스템이 중지되었습니다", "stopped")
    }

    @Operation(summary = "풀 시스템 상태 조회")
    @GetMapping("/system/health")
    suspend fun getSystemHealth(): SystemHealthResponse {
        val pools = poolService.getAllPools()
        return SystemHealthResponse(
            pools = PoolCounts(total = pools.size, active = pools.count { it.isActive }),
            oracle = oracleService.getOracleStatus(),
            lastUpdate = clock.nowMillis(),
        )
    }
}
