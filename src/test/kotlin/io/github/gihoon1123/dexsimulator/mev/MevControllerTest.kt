package io.github.gihoon1123.dexsimulator.mev

import org.hamcrest.Matchers.startsWith
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
class MevControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private fun call(request: RequestBuilder): ResultActions = mockMvc.perform(asyncDispatch(mockMvc.perform(request).andReturn()))

    @Test
    fun `mev apis require block production and the transaction generator`() {
        call(get("/mev/bot/status"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(startsWith("블록체인 시뮬레이션을 위해서는 블록 자동생성이")))

        call(post("/blockchain/auto-production/start"))
        call(get("/mev/stats"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(startsWith("블록체인 시뮬레이션을 위해서는 트랜잭션 자동생성이")))
    }

    @Test
    fun `bot lifecycle once prerequisites run`() {
        call(post("/blockchain/auto-production/start"))
        call(post("/blockchain/tx-generator/start"))

        call(
            post(
                "/mev/bot/start",
            ).contentType(MediaType.APPLICATION_JSON).content("""{"minProfit":0.02,"enabledStrategies":["SANDWICH"]}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.botState.status").value("RUNNING"))
            .andExpect(jsonPath("$.botState.config.minProfit").value(0.02))
            .andExpect(jsonPath("$.botState.config.enabledStrategies[0]").value("SANDWICH"))

        call(get("/mev/opportunities?limit=5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").exists())
        call(get("/mev/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.botStatus").value("RUNNING"))
            .andExpect(jsonPath("$.strategyBreakdown.SANDWICH.count").value(0))

        call(post("/mev/bot/stop"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.message").value("MEV 봇이 중지되었습니다"))
    }
}
