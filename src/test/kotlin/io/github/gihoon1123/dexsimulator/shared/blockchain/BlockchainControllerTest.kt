package io.github.gihoon1123.dexsimulator.shared.blockchain

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

/** HTTP contract checks for the blockchain routes. Each test gets a fresh simulation. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BlockchainControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private fun call(request: RequestBuilder): ResultActions = mockMvc.perform(asyncDispatch(mockMvc.perform(request).andReturn()))

    @Test
    fun `latest block before any block answers a message`() {
        call(get("/blockchain/blocks/latest"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("아직 생성된 블록이 없습니다"))
    }

    @Test
    fun `submitted transaction keeps its type and is produced into a block`() {
        val body = """{"type":"transfer","from":"user-0x1234","to":"0xabc","data":"0x","gasLimit":100000}"""

        call(post("/blockchain/submit-transaction").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.transaction.type").value("transfer"))
            .andExpect(jsonPath("$.transaction.status").value("pending"))
            .andExpect(jsonPath("$.transaction.nonce").value(0))
            .andExpect(jsonPath("$.poolStatus.position").value(1))
            .andExpect(jsonPath("$.poolStatus.estimatedBlocksUntilExecution").value(1))

        call(post("/blockchain/produce-block"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.message").value("블록 #1이 생성되고 실행되었습니다"))
            .andExpect(jsonPath("$.block.transactionCount").value(1))
            .andExpect(jsonPath("$.result.executedTransactions[0].status").value("completed"))

        call(get("/blockchain/blocks/1")).andExpect(jsonPath("$.blockNumber").value(1))
        call(get("/blockchain/blocks/9")).andExpect(jsonPath("$.message").value("블록 #9을 찾을 수 없습니다"))
    }

    @Test
    fun `gas estimate validates the type`() {
        mockMvc.perform(get("/blockchain/gas/estimate?type=bogus")).andExpect(status().isBadRequest)
        mockMvc
            .perform(get("/blockchain/gas/estimate?type=transfer"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gasPrice").value(100))
    }

    @Test
    fun `stats aggregate chain, mempool and generator`() {
        call(get("/blockchain/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.blockchain.totalBlocks").value(0))
            .andExpect(jsonPath("$.autoProduction.isActive").value(false))
            .andExpect(jsonPath("$.autoProduction.interval").value(12000))
            .andExpect(jsonPath("$.txGenerator.isGenerating").value(false))
    }
}
