package io.github.gihoon1123.dexsimulator.contract

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ContractSimulationControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private fun call(request: RequestBuilder): ResultActions = mockMvc.perform(asyncDispatch(mockMvc.perform(request).andReturn()))

    private fun json(
        url: String,
        body: String,
    ) = post(url).contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `pool lookups use the success envelope`() {
        call(get("/contract-simulation/pools?token=BTC&sortBy=liquidity&sortOrder=desc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].poolId").value("pool_btc_usdc"))
        call(get("/contract-simulation/pools/pool_eth_usdc"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.pool.reserveA").value(10000))
        call(get("/contract-simulation/pools/nope"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("풀을 찾을 수 없습니다"))
        call(get("/contract-simulation/pools/find/by-tokens?tokenA=USDT&tokenB=DAI"))
            .andExpect(jsonPath("$.pool.poolId").value("pool_dai_usdt"))
    }

    @Test
    fun `duplicate pool answers success false`() {
        call(
            json(
                "/contract-simulation/pools",
                """{"tokenA":"ETH","tokenB":"USDC","initialReserveA":1,"initialReserveB":1,"feeRate":0.003}""",
            ),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("풀이 이미 존재합니다: pool_eth_usdc"))
    }

    @Test
    fun `swap, impact comparison and stats`() {
        call(json("/contract-simulation/swap", """{"poolId":"pool_eth_usdc","tokenIn":"ETH","tokenOut":"USDC","amountIn":1}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.swapId").exists())
        call(get("/contract-simulation/compare-price-impacts?poolId=pool_eth_usdc&tokenIn=ETH&amounts=1,10,100"))
            .andExpect(jsonPath("$.comparisons.length()").value(3))
            .andExpect(jsonPath("$.comparisons[0].level").value("low"))
        call(get("/contract-simulation/stats"))
            .andExpect(jsonPath("$.totalPools").value(10))
            .andExpect(jsonPath("$.totalSwaps").value(1))
            .andExpect(jsonPath("$.mostActivePool").value("pool_eth_usdc"))
    }

    @Test
    fun `router endpoints`() {
        call(get("/contract-simulation/find-routes?tokenIn=ETH&tokenOut=DAI&amountIn=1"))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.result.directRoutes").value(1))
        call(json("/contract-simulation/compare-routes", """{"tokenIn":"ETH","tokenOut":"XYZ","amountIn":1}"""))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("가능한 경로를 찾을 수 없습니다"))
        call(
            json(
                "/contract-simulation/auto-swap",
                """{"tokenIn":"ETH","tokenOut":"DAI","amountIn":50,"slippageTolerance":100,"options":{"slippageTolerance":100}}""",
            ),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.routeUsed.type").value("multi-hop"))
    }
}
