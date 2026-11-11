package io.github.gihoon1123.dexsimulator.shared.pool

import io.github.gihoon1123.dexsimulator.shared.pool.PoolServiceTest.Companion.ETH_USDC
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP contract checks: routes, status codes and JSON shape match the original. */
@SpringBootTest
@AutoConfigureMockMvc
class PoolControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    // `suspend` handlers complete asynchronously, so the result is read with asyncDispatch.
    private fun call(request: RequestBuilder): ResultActions = mockMvc.perform(asyncDispatch(mockMvc.perform(request).andReturn()))

    private fun json(
        url: String,
        body: String,
    ) = post(url).contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `pool list keeps original field names`() {
        call(get("/pool/list?token=ETH&sortBy=price&sortOrder=desc&limit=1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.pageSize").value(1))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.pools[0].pair").value("ETH/USDC"))
            .andExpect(jsonPath("$.pools[0].isActive").value(true))
    }

    @Test
    fun `amounts are written as plain numbers without trailing zeros`() {
        val body =
            call(get("/pool/$ETH_USDC"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertTrue("\"reserveB\":2000000" in body || "\"reserveB\":2" in body, body)
        assertFalse("E+" in body || ".000" in body, body)
        assertFalse("decimalsA" in body, body)
    }

    @Test
    fun `missing pool answers 200 with an empty body like NestJS null`() {
        call(get("/pool/0xnone"))
            .andExpect(status().isOk)
            .andExpect(content().string(""))
    }

    @Test
    fun `swap simulation answers 201`() {
        call(json("/pool/swap/simulate", """{"poolAddress":"$ETH_USDC","tokenIn":"ETH","tokenOut":"USDC","amountIn":1}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.isExecutable").value(true))
            .andExpect(jsonPath("$.errors").isEmpty)
    }

    @Test
    fun `invalid body answers 400 with the field error`() {
        val body =
            """{"poolAddress":"$ETH_USDC","tokenIn":"ETH","tokenOut":"USDC","amountIn":-1,""" +
                """"amountOutMin":0,"recipient":"u","deadline":9999999999}"""

        mockMvc
            .perform(json("/pool/swap/execute", body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.statusCode").value(400))
            .andExpect(jsonPath("$.message").value(startsWith("amountIn:")))
    }

    @Test
    fun `invalid query answers 400`() {
        mockMvc.perform(get("/pool/list?limit=0")).andExpect(status().isBadRequest)
    }

    @Test
    fun `system health reports pools and oracle`() {
        call(get("/pool/system/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pools.total").value(4))
            .andExpect(jsonPath("$.oracle.isActive").value(true))
            .andExpect(jsonPath("$.oracle.systemStatus").value("healthy"))
    }
}
