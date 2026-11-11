package io.github.gihoon1123.dexsimulator.shared.blockchain

import io.github.gihoon1123.dexsimulator.common.sim.nowMillis
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import kotlin.random.Random

/**
 * Same routes and bodies as the original `BlockchainController`. Missing blocks and
 * transactions answer 200 with a message, as in the original.
 */
@Tag(name = "Blockchain")
@RestController
@RequestMapping("/blockchain")
class BlockchainController(
    private val blockService: BlockService,
    private val transactionPool: TransactionPoolService,
    private val gasService: GasService,
    private val generator: TransactionGeneratorService,
    private val random: Random,
    private val clock: Clock,
) {
    @Operation(summary = "블록체인 상태 조회")
    @GetMapping("/status")
    suspend fun getBlockchainStatus(): BlockchainStatus = blockService.getBlockchainStatus()

    @Operation(summary = "블록 목록 조회")
    @GetMapping("/blocks")
    suspend fun getBlocks(
        @Valid @ModelAttribute query: GetBlocksQuery,
    ): BlockPage {
        val limit = query.limit ?: DEFAULT_BLOCK_LIMIT
        val offset = query.offset ?: 0
        val all = blockService.getBlockchain()
        return BlockPage(all.drop(offset).take(limit), all.size, offset / limit + 1, limit)
    }

    @Operation(summary = "최신 블록 조회")
    @GetMapping("/blocks/latest")
    suspend fun getLatestBlock(): Any = blockService.getLatestBlock() ?: NotFoundMessage("아직 생성된 블록이 없습니다")

    @Operation(summary = "블록 번호로 조회")
    @GetMapping("/blocks/{blockNumber}")
    suspend fun getBlockByNumber(
        @PathVariable blockNumber: String,
    ): Any =
        blockNumber.toLongOrNull()?.let { blockService.getBlockByNumber(it) }
            ?: NotFoundMessage("블록 #${blockNumber}을 찾을 수 없습니다")

    @Operation(summary = "트랜잭션 풀 상태 조회")
    @GetMapping("/tx-pool/status")
    suspend fun getTransactionPoolStatus(): TransactionPoolStatus = transactionPool.getPoolStatus()

    @Operation(summary = "대기 중인 트랜잭션 조회")
    @GetMapping("/tx-pool/pending")
    suspend fun getPendingTransactions(
        @Valid @ModelAttribute query: GetTransactionsQuery,
    ): PendingTransactions {
        val all = transactionPool.getPendingTransactions()
        return PendingTransactions(all.take(query.limit ?: DEFAULT_TX_LIMIT), all.size)
    }

    @Operation(summary = "트랜잭션 상세 조회")
    @GetMapping("/tx-pool/transactions/{txId}")
    suspend fun getTransactionById(
        @PathVariable txId: String,
    ): Any = transactionPool.getTransactionById(txId) ?: NotFoundMessage("트랜잭션 ${txId}을 찾을 수 없습니다")

    @Operation(summary = "트랜잭션 풀 초기화")
    @DeleteMapping("/tx-pool/clear")
    suspend fun clearTransactionPool(): ClearPoolResponse = ClearPoolResponse("트랜잭션 풀이 초기화되었습니다", transactionPool.clearPool())

    @Operation(summary = "블록 생성 및 실행")
    @PostMapping("/produce-block")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun produceBlock(): ProduceBlockResponse {
        val result = blockService.produceBlock()
        return ProduceBlockResponse("블록 #${result.block.blockNumber}이 생성되고 실행되었습니다", result.block, result)
    }

    @Operation(summary = "자동 블록 생성 시작")
    @PostMapping("/auto-production/start")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun startAutoProduction(): ToggleResponse {
        blockService.startAutoProduction()
        return ToggleResponse("자동 블록 생성이 시작되었습니다", isActive = true, interval = BLOCK_INTERVAL_MILLIS)
    }

    @Operation(summary = "자동 블록 생성 중지")
    @PostMapping("/auto-production/stop")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun stopAutoProduction(): ToggleResponse {
        blockService.stopAutoProduction()
        return ToggleResponse("자동 블록 생성이 중지되었습니다", isActive = false)
    }

    @Operation(summary = "자동 블록 생성 상태 조회")
    @GetMapping("/auto-production/status")
    suspend fun getAutoProductionStatus(): AutoProductionStatus {
        val active = blockService.isAutoProduction()
        return AutoProductionStatus(
            isActive = active,
            interval = BLOCK_INTERVAL_MILLIS,
            message = if (active) "자동 블록 생성이 실행 중입니다" else "자동 블록 생성이 중지되어 있습니다",
        )
    }

    /** The original set a time-based nonce and forgot the type; here both come from the pool and the request. */
    @Operation(summary = "트랜잭션 제출")
    @PostMapping("/submit-transaction")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun submitTransaction(
        @Valid @RequestBody request: SubmitTransactionRequest,
    ): SubmitTransactionResponse {
        val type = TransactionType.of(request.type)
        val now = clock.nowMillis()
        val submitted =
            transactionPool.submit(
                Transaction(
                    id = "tx_${now.toEpochMilli()}_${randomSuffix()}",
                    type = type,
                    from = request.from,
                    to = request.to,
                    value = request.value ?: "0",
                    data = request.data,
                    gasPrice = request.gasPrice ?: gasService.getCurrentGasPrice(),
                    gasLimit = request.gasLimit ?: gasService.estimateGas(type),
                    status = TransactionStatus.PENDING,
                    nonce = transactionPool.getCurrentNonce(request.from),
                    timestamp = now,
                    parsedData = SwapAbi.decode(request.data),
                ),
            )
        val pending = transactionPool.getPendingTransactions()
        val position = pending.indexOfFirst { it.id == submitted.id } + 1
        return SubmitTransactionResponse(
            message = "트랜잭션이 풀에 제출되었습니다",
            transaction = submitted,
            poolStatus = SubmitPosition(pending.size, position, (position + TXS_PER_BLOCK_ESTIMATE - 1) / TXS_PER_BLOCK_ESTIMATE),
        )
    }

    @Operation(summary = "현재 가스 가격 조회")
    @GetMapping("/gas/current-price")
    fun getCurrentGasPrice(): GasPriceResponse {
        val current = gasService.getCurrentGasPrice()
        return GasPriceResponse(
            baseGasPrice = current,
            currentNetworkPrice = current,
            suggested =
                SuggestedGasPrices(
                    low = gasService.calculateGasPrice(GasPriority.LOW),
                    medium = gasService.calculateGasPrice(GasPriority.MEDIUM),
                    high = gasService.calculateGasPrice(GasPriority.HIGH),
                    urgent = gasService.calculateGasPrice(GasPriority.URGENT),
                ),
        )
    }

    @Operation(summary = "가스 추정")
    @GetMapping("/gas/estimate")
    fun estimateGas(
        @Valid @ModelAttribute query: EstimateGasQuery,
    ): GasEstimateResponse {
        val estimated = gasService.estimateGas(TransactionType.of(query.type))
        val price = gasService.getCurrentGasPrice()
        return GasEstimateResponse(estimated, price, gasService.calculateTotalCost(estimated, price))
    }

    @Operation(summary = "트랜잭션 자동 생성 시작")
    @PostMapping("/tx-generator/start")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun startTransactionGenerator(): ToggleResponse {
        generator.startGenerating()
        return ToggleResponse("트랜잭션 자동 생성이 시작되었습니다", isActive = true, interval = "1초 (현재)")
    }

    @Operation(summary = "트랜잭션 자동 생성 중지")
    @PostMapping("/tx-generator/stop")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun stopTransactionGenerator(): ToggleResponse {
        generator.stopGenerating()
        return ToggleResponse("트랜잭션 자동 생성이 중지되었습니다", isActive = false)
    }

    @Operation(summary = "트랜잭션 생성기 상태 조회")
    @GetMapping("/tx-generator/status")
    suspend fun getTransactionGeneratorStatus(): TransactionGeneratorStats = generator.getStats()

    @Operation(summary = "트랜잭션 1개 생성")
    @PostMapping("/tx-generator/generate-one")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun generateOneTransaction(): GeneratedTransactionResponse =
        GeneratedTransactionResponse("트랜잭션이 생성되었습니다", generator.generateRandomTransaction())

    @Operation(summary = "블록체인 통계 조회")
    @GetMapping("/stats")
    suspend fun getStats(): BlockchainStatsResponse {
        val poolStatus = transactionPool.getPoolStatus()
        return BlockchainStatsResponse(
            blockchain = blockService.getBlockchainStatus(),
            txPool = poolStatus,
            gas = GasStats(gasService.getCurrentGasPrice(), poolStatus.averageGasPrice),
            autoProduction = AutoProductionInfo(blockService.isAutoProduction(), BLOCK_INTERVAL_MILLIS),
            txGenerator = generator.getStats(),
        )
    }

    private fun randomSuffix(): String = (1..RANDOM_SUFFIX_LENGTH).map { BASE36[random.nextInt(BASE36.length)] }.joinToString("")

    companion object {
        private const val DEFAULT_BLOCK_LIMIT = 10
        private const val DEFAULT_TX_LIMIT = 20
        private const val TXS_PER_BLOCK_ESTIMATE = 10
        private const val RANDOM_SUFFIX_LENGTH = 9
        private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
        private val BLOCK_INTERVAL_MILLIS = BlockService.BLOCK_INTERVAL.toMillis()
    }
}
