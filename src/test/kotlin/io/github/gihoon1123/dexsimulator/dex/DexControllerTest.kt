package io.github.gihoon1123.dexsimulator.dex

import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Ported from the original market, lp and trader controller specs: routes, status codes and JSON shape. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DexControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private fun call(request: RequestBuilder): ResultActions = mockMvc.perform(asyncDispatch(mockMvc.perform(request).andReturn()))

    @Test
    fun `market price and status`() {
        call(get("/market/price"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.eth").value(2000.0))
            .andExpect(jsonPath("$.btc").value(60000.0))
        call(get("/market/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.volatility.overall").value(0.0))
            .andExpect(jsonPath("$.arbitrageOpportunity").isEmpty)
    }

    @Test
    fun `price change answers 201 without the internal event type`() {
        call(post("/market/simulate-price-change"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.eventId").value(startsWith("price_change_")))
            .andExpect(jsonPath("$.previousPrice.eth").value(2000.0))
            .andExpect(jsonPath("$.type").doesNotExist())
    }

    @Test
    fun `lp operations before init answer 400 with the original message`() {
        val message = "풀이 초기화되지 않았습니다. 먼저 POST /lp/init을 호출해주세요."
        listOf(get("/lp/status"), post("/lp/add-user"), delete("/lp/remove-user"), post("/trader/execute-random-trade"))
            .forEach { request ->
                call(request)
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.message").value(message))
            }
        call(post("/market/check-arbitrage")).andExpect(status().isBadRequest)
    }

    @Test
    fun `lp lifecycle and a trade`() {
        call(post("/lp/init"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.eth").value(1000))
            .andExpect(jsonPath("$.btc").value(30000))
            .andExpect(jsonPath("$.k").value(30000000))
            .andExpect(jsonPath("$.userCount").value(10))
            .andExpect(jsonPath("$.users[0].governanceTokens").exists())
        call(post("/lp/add-user")).andExpect(status().isCreated)
        call(delete("/lp/remove-user")).andExpect(status().isOk)

        call(post("/trader/execute-random-trade"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.trade.id").value("trade_1"))
            .andExpect(jsonPath("$.trade.from").value(org.hamcrest.Matchers.oneOf("ETH", "BTC")))
            .andExpect(jsonPath("$.priceInfo.expectedRate").exists())
            .andExpect(jsonPath("$.poolAfter.k").exists())

        call(post("/trader/execute-arbitrage"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.message").exists())
        call(post("/market/check-arbitrage"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.message").value("아비트라지 기회 체크 완료"))
    }
}
