package io.github.gihoon1123.dexsimulator.shared.blockchain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwapAbiTest {
    private val recipient = "0x00000000000000000000000000000000000000aa"

    @Test
    fun `encodes the uniswap v3 swap layout`() {
        val callData = SwapAbi.encode(SwapCallArgs(recipient, true, "1000", "0", "0x"))

        assertEquals("0x128acb08", callData.take(10))
        // 5 head words + bytes length word, no bytes content.
        assertEquals(10 + 6 * 64, callData.length)
        val words = callData.drop(10).chunked(64)
        assertEquals("aa", words[0].takeLast(2))
        assertEquals("1", words[1].trimStart('0'))
        assertEquals("3e8", words[2].trimStart('0'))
        assertEquals("a0", words[4].trimStart('0'))
    }

    @Test
    fun `decoding returns what was encoded`() {
        val args = SwapCallArgs(recipient, false, "-123456789012345678901234567890", "79228162514264337593543950336", "0xdeadbeef")

        val parsed = SwapAbi.decode(SwapAbi.encode(args))

        assertEquals("swap", parsed?.function)
        assertEquals(args, parsed?.params)
    }

    @Test
    fun `simulation user names map to a stable pseudo address`() {
        // First 20 bytes of sha256("user-1"), computed separately.
        assertEquals("0xc6c289e49e9c05b2145860387b73bcb18df43fb0", Addresses.of("user-1"))
        assertEquals(recipient, Addresses.of(recipient.uppercase().replace("0X", "0x")))
        val parsed = SwapAbi.decode(SwapAbi.encode(SwapCallArgs("user-1", true, "1", "0", "0x")))
        assertEquals(Addresses.of("user-1"), parsed?.params?.recipient)
    }

    @Test
    fun `malformed call data is not decoded`() {
        assertNull(SwapAbi.decode("0x"))
        assertNull(SwapAbi.decode("0x12345678" + "0".repeat(384)))
        assertNull(SwapAbi.decode("0x128acb08" + "0".repeat(10)))
    }

    @Test
    fun `values outside the abi types are rejected`() {
        assertThrows<IllegalArgumentException> { SwapAbi.encode(SwapCallArgs(recipient, true, "1.5", "0", "0x")) }
        assertThrows<IllegalArgumentException> { SwapAbi.encode(SwapCallArgs(recipient, true, "1", "-1", "0x")) }
        assertThrows<IllegalArgumentException> { SwapAbi.encode(SwapCallArgs(recipient, true, "1", "0", "0xzz")) }
    }
}
