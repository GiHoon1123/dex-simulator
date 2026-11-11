package io.github.gihoon1123.dexsimulator.contract

import io.github.gihoon1123.dexsimulator.common.sim.SimulationContext
import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.github.gihoon1123.dexsimulator.contract.SingletonContractService.Companion.format2
import io.github.gihoon1123.dexsimulator.contract.SingletonContractService.Companion.plain
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock

/**
 * Router over the singleton pools, ported from the original `RouterContractService`:
 * breadth-first path search up to 3 hops, route quotes, best route by output, and
 * multi-hop execution.
 *
 * Fixed in the migration (decisions #14): multi-hop execution was not atomic. A failing hop
 * left the earlier hops applied, and a route below `minAmountOut` reported failure after
 * every hop had already changed its pool. Here the whole route is checked first and the
 * hops are applied together within one simulation step.
 */
@Service
class RouterContractService(
    private val sim: SimulationContext,
    private val singleton: SingletonContractService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var routeCounter = 0L
    private var swapCounter = 0L

    suspend fun autoSwap(params: AutoSwapParams): AutoSwapResult =
        sim.run {
            val started = System.nanoTime()
            val routes = findRoutes(params.tokenIn, params.tokenOut, params.amountIn, params.options)
            if (routes.isEmpty()) {
                return@run failure(null, "가능한 경로를 찾을 수 없습니다", started)
            }
            val best = selectBest(routes)
            if (!best.feasible) return@run failure(best, best.errors.joinToString(", "), started)
            execute(best, params.minAmountOut ?: BigDecimal.ZERO, started)
        }

    suspend fun findAllRoutes(
        tokenIn: String,
        tokenOut: String,
        amountIn: BigDecimal,
        options: RouteSearchOptions? = null,
    ): List<Route> = sim.run { findRoutes(tokenIn, tokenOut, amountIn, options) }

    suspend fun searchRoutes(
        tokenIn: String,
        tokenOut: String,
        amountIn: BigDecimal,
        options: RouteSearchOptions? = null,
    ): RouteSearchResult =
        sim.run {
            val started = System.nanoTime()
            val routes = findRoutes(tokenIn, tokenOut, amountIn, options)
            RouteSearchResult(
                tokenIn = tokenIn,
                tokenOut = tokenOut,
                amountIn = amountIn,
                routes = routes,
                totalRoutes = routes.size,
                directRoutes = routes.count { it.type == RouteType.DIRECT },
                twoHopRoutes = routes.count { it.hops == 2 },
                threeHopRoutes = routes.count { it.hops == 3 },
                searchTime = elapsedMillis(started),
            )
        }

    suspend fun compareRoutes(
        tokenIn: String,
        tokenOut: String,
        amountIn: BigDecimal,
        options: RouteSearchOptions? = null,
    ): RouteComparison =
        sim.run {
            val routes = findRoutes(tokenIn, tokenOut, amountIn, options)
            require(routes.isNotEmpty()) { "가능한 경로를 찾을 수 없습니다" }
            val best = selectBest(routes)
            val direct = routes.firstOrNull { it.type == RouteType.DIRECT }
            val bestMultiHop = routes.filter { it.type == RouteType.MULTI_HOP && it.feasible }.maxByOrNull { it.expectedAmountOut }
            val singletonGas = best.gasEstimate
            val regularGas = regularDexGas(best.hops)
            RouteComparison(
                tokenIn = tokenIn,
                tokenOut = tokenOut,
                amountIn = amountIn,
                allRoutes = routes,
                bestRoute = best,
                directRoute = direct,
                bestMultiHopRoute = bestMultiHop,
                comparison = if (direct != null && bestMultiHop != null) diff(direct, bestMultiHop) else null,
                singletonAdvantage =
                    SingletonAdvantage(
                        singletonGas = singletonGas,
                        regularDexGas = regularGas,
                        gasSaved = regularGas - singletonGas,
                        gasSavedPercent = (regularGas - singletonGas) * 100.0 / regularGas,
                    ),
            )
        }

    /** Executes [route] all-or-nothing. Must be called on the simulation thread. */
    private fun execute(
        route: Route,
        minAmountOut: BigDecimal,
        started: Long,
    ): AutoSwapResult {
        // Hops use distinct pools (paths never revisit a token), so every hop can be quoted
        // on the current state before anything is applied.
        var amount = route.hopsDetail.first().amountIn
        val planned =
            route.hopsDetail.map { hop ->
                val params = SingletonSwapParams(hop.poolId, hop.tokenIn, hop.tokenOut, amount)
                val quote = singleton.quote(params)
                if (!quote.isExecutable) return failure(route, quote.errors.joinToString(", "), started)
                amount = quote.expectedAmountOut
                params to quote
            }
        if (amount < minAmountOut) {
            return failure(
                route,
                "최소 출력량 미달: ${amount.setScale(
                    6,
                    java.math.RoundingMode.DOWN,
                ).toPlainString()} < ${minAmountOut.stripTrailingZeros().toPlainString()}",
                started,
            )
        }

        val executed =
            planned.map { (params, quote) ->
                val result = singleton.apply(params, quote)
                ExecutedHop(
                    params.poolId,
                    result.swapId,
                    params.tokenIn,
                    params.tokenOut,
                    result.amountIn,
                    result.amountOut,
                    result.priceImpact,
                    result.timestamp,
                )
            }
        log.info("멀티홉 스왑 완료: {} ({})", route.path.joinToString(" → "), amount.toPlainString())
        return AutoSwapResult(
            swapId = "multiswap_${++swapCounter}_${clock.millis()}",
            routeUsed = route,
            hopsExecuted = executed,
            finalAmountOut = amount,
            totalPriceImpact = route.priceImpact,
            // Gas split evenly over hops, as in the original.
            totalGasUsed = route.gasEstimate,
            totalFee = planned.fold(BigDecimal.ZERO) { sum, (_, quote) -> sum + quote.fee },
            executionTime = elapsedMillis(started),
            success = true,
            timestamp = clock.nowMillis(),
        )
    }

    private fun findRoutes(
        tokenIn: String,
        tokenOut: String,
        amountIn: BigDecimal,
        options: RouteSearchOptions?,
    ): List<Route> = searchPaths(tokenIn, tokenOut, options?.maxHops ?: DEFAULT_MAX_HOPS).map { quoteRoute(it, amountIn, options) }

    /** Breadth-first search over pools, never revisiting a token. */
    private fun searchPaths(
        tokenIn: String,
        tokenOut: String,
        maxHops: Int,
    ): List<List<String>> {
        val pools = singleton.poolsSnapshot()
        val paths = ArrayList<List<String>>()
        val queue = ArrayDeque(listOf(listOf(tokenIn)))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            if (current == tokenOut) {
                paths += path
                continue
            }
            if (path.size > maxHops) continue
            pools
                .filter { it.tokenA == current || it.tokenB == current }
                .map { if (it.tokenA == current) it.tokenB else it.tokenA }
                .filter { it !in path }
                .forEach { queue.addLast(path + it) }
        }
        return paths
    }

    private fun quoteRoute(
        path: List<String>,
        amountIn: BigDecimal,
        options: RouteSearchOptions?,
    ): Route {
        val hopTolerance = options?.slippageTolerance ?: HOP_SLIPPAGE_DEFAULT
        val routeTolerance = options?.slippageTolerance ?: ROUTE_SLIPPAGE_DEFAULT
        val hops = ArrayList<HopDetail>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        var amount = amountIn
        var totalFee = BigDecimal.ZERO
        var totalImpact = 0.0

        path.zipWithNext().forEach { (tokenIn, tokenOut) ->
            val poolId = singleton.poolIdOf(tokenIn, tokenOut)
            val quote = singleton.quote(SingletonSwapParams(poolId, tokenIn, tokenOut, amount, slippageTolerance = hopTolerance))
            if (!quote.isExecutable) errors += quote.errors
            warnings += quote.warnings
            hops += HopDetail(poolId, tokenIn, tokenOut, amount, quote.expectedAmountOut, quote.priceImpact)
            totalFee += quote.fee
            totalImpact += quote.priceImpact
            amount = quote.expectedAmountOut
        }

        if (totalImpact > routeTolerance) {
            warnings += "총 가격 임팩트가 슬리피지 허용 범위를 초과합니다: ${format2(totalImpact)}% > ${plain(routeTolerance)}%"
        }
        var reason = ""
        if (totalImpact > MAX_ROUTE_IMPACT) {
            errors += "가격 임팩트가 너무 높습니다 (10% 초과)"
            reason = "가격 임팩트 너무 높음"
        }
        val hopCount = path.size - 1
        return Route(
            routeId = "route_${++routeCounter}_${clock.millis()}",
            path = path,
            pools = hops.map { it.poolId },
            type = if (hopCount == 1) RouteType.DIRECT else RouteType.MULTI_HOP,
            hops = hopCount,
            expectedAmountOut = amount,
            priceImpact = totalImpact,
            hopsDetail = hops,
            gasEstimate = BASE_GAS + hopCount * GAS_PER_HOP,
            totalFee = totalFee,
            feasible = errors.isEmpty(),
            warnings = warnings,
            errors = errors,
            recommended = false,
            reason = reason,
        )
    }

    /** Highest output among feasible routes, marked as recommended; otherwise the highest output. */
    private fun selectBest(routes: List<Route>): Route {
        val feasible = routes.filter { it.feasible }
        if (feasible.isEmpty()) return routes.maxBy { it.expectedAmountOut }
        val best = feasible.maxBy { it.expectedAmountOut }
        return best.copy(
            recommended = true,
            reason = "최대 출력량: ${best.expectedAmountOut.setScale(6, java.math.RoundingMode.DOWN).toPlainString()}",
        )
    }

    private fun diff(
        direct: Route,
        multiHop: Route,
    ): RouteDiff {
        val outputDiff = multiHop.expectedAmountOut - direct.expectedAmountOut
        val outputDiffPercent =
            if (direct.expectedAmountOut.signum() ==
                0
            ) {
                0.0
            } else {
                outputDiff.divide(direct.expectedAmountOut, MathContext.DECIMAL64).toDouble() * 100
            }
        val impactDiff = direct.priceImpact - multiHop.priceImpact
        val gasDiff = multiHop.gasEstimate - direct.gasEstimate
        val (recommendation, reason) =
            when {
                outputDiff.signum() > 0 && kotlin.math.abs(outputDiffPercent) > 5 ->
                    RouteType.MULTI_HOP to "멀티홉이 ${String.format(java.util.Locale.ROOT, "%.1f", outputDiffPercent)}% 더 많은 출력량 제공"
                gasDiff > 50_000 -> RouteType.DIRECT to "가스비 ${String.format(java.util.Locale.US, "%,d", gasDiff)} 절약"
                impactDiff > 5 -> RouteType.MULTI_HOP to "가격 임팩트 ${String.format(java.util.Locale.ROOT, "%.1f", impactDiff)}% 낮음"
                else -> (if (outputDiff.signum() > 0) RouteType.MULTI_HOP else RouteType.DIRECT) to "출력량 기준"
            }
        return RouteDiff(outputDiff, outputDiffPercent, impactDiff, gasDiff, recommendation, reason)
    }

    private fun failure(
        route: Route?,
        error: String,
        started: Long,
    ) = AutoSwapResult(
        swapId = "",
        routeUsed = route,
        hopsExecuted = emptyList(),
        finalAmountOut = BigDecimal.ZERO,
        totalPriceImpact = 0.0,
        totalGasUsed = 0,
        totalFee = BigDecimal.ZERO,
        executionTime = elapsedMillis(started),
        success = false,
        timestamp = clock.nowMillis(),
        error = error,
    )

    /** A regular DEX pays a full swap per hop; the singleton pays the base once. */
    private fun regularDexGas(hops: Int): Long = if (hops == 1) BASE_GAS + GAS_PER_HOP else (BASE_GAS + GAS_PER_HOP) * hops

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val BASE_GAS = 100_000L
        const val GAS_PER_HOP = 50_000L
        private const val DEFAULT_MAX_HOPS = 3
        private const val MAX_ROUTE_IMPACT = 10.0
        private const val HOP_SLIPPAGE_DEFAULT = 100.0
        private const val ROUTE_SLIPPAGE_DEFAULT = 0.5
    }
}
