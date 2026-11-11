package io.github.gihoon1123.dexsimulator.shared.blockchain

import java.math.BigInteger
import java.security.MessageDigest

/**
 * ABI codec for Uniswap V3 `swap(address,bool,int256,uint160,bytes)`.
 *
 * The original encoded every swap as the selector followed by zeros and "decoded" it into
 * random values. Here the call data is real ABI and decoding returns what was encoded.
 */
object SwapAbi {
    /** First 4 bytes of keccak256("swap(address,bool,int256,uint160,bytes)"). */
    const val SELECTOR = "0x128acb08"
    const val FUNCTION_NAME = "swap"

    private const val WORD_HEX = 64
    private const val HEAD_WORDS = 5
    private val UINT160_MAX = BigInteger.ONE.shiftLeft(160) - BigInteger.ONE
    private val INT256_MIN = BigInteger.ONE.shiftLeft(255).negate()
    private val INT256_MAX = BigInteger.ONE.shiftLeft(255) - BigInteger.ONE
    private val TWO_256 = BigInteger.ONE.shiftLeft(256)

    fun encode(args: SwapCallArgs): String {
        val amount = BigInteger(args.amountSpecified)
        require(amount in INT256_MIN..INT256_MAX) { "amountSpecified가 int256 범위를 벗어났습니다" }
        val priceLimit = BigInteger(args.sqrtPriceLimitX96)
        require(priceLimit.signum() >= 0 && priceLimit <= UINT160_MAX) { "sqrtPriceLimitX96가 uint160 범위를 벗어났습니다" }
        val bytes = hexBody(args.data)

        return buildString {
            append(SELECTOR)
            append(word(BigInteger(Addresses.of(args.recipient).removePrefix("0x"), 16)))
            append(word(if (args.zeroForOne) BigInteger.ONE else BigInteger.ZERO))
            append(word(if (amount.signum() < 0) amount + TWO_256 else amount))
            append(word(priceLimit))
            append(word(BigInteger.valueOf((HEAD_WORDS * 32).toLong())))
            append(word(BigInteger.valueOf((bytes.length / 2).toLong())))
            if (bytes.isNotEmpty()) append(bytes.padEnd(((bytes.length + WORD_HEX - 1) / WORD_HEX) * WORD_HEX, '0'))
        }
    }

    /** Returns null for call data that is not a well-formed `swap` call. */
    fun decode(callData: String): ParsedTransactionData? {
        if (!callData.startsWith(SELECTOR)) return null
        val body = callData.removePrefix(SELECTOR)
        if (body.length < (HEAD_WORDS + 1) * WORD_HEX || body.length % WORD_HEX != 0) return null

        fun word(index: Int) = BigInteger(body.substring(index * WORD_HEX, (index + 1) * WORD_HEX), 16)

        val amountWord = word(2)
        val bytesOffset = word(4).toInt() * 2
        if (bytesOffset + WORD_HEX > body.length) return null
        val bytesLength = BigInteger(body.substring(bytesOffset, bytesOffset + WORD_HEX), 16).toInt() * 2
        if (bytesOffset + WORD_HEX + bytesLength > body.length) return null

        return ParsedTransactionData(
            function = FUNCTION_NAME,
            params =
                SwapCallArgs(
                    recipient = "0x" + word(0).toString(16).padStart(40, '0'),
                    zeroForOne = word(1).signum() != 0,
                    amountSpecified = (if (amountWord.testBit(255)) amountWord - TWO_256 else amountWord).toString(),
                    sqrtPriceLimitX96 = word(3).toString(),
                    data = "0x" + body.substring(bytesOffset + WORD_HEX, bytesOffset + WORD_HEX + bytesLength),
                ),
        )
    }

    private fun word(value: BigInteger) = value.toString(16).padStart(WORD_HEX, '0')

    private fun hexBody(data: String): String {
        val body = data.removePrefix("0x")
        require(body.length % 2 == 0 && body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "data는 hex 바이트여야 합니다" }
        return body.lowercase()
    }
}

/**
 * Simulation users are names such as `user-12`, not addresses. ABI needs 20 bytes, so a
 * name is mapped to a stable pseudo address (first 20 bytes of its SHA-256). Real
 * addresses are kept as they are.
 */
object Addresses {
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

    fun of(nameOrAddress: String): String {
        if (ADDRESS.matches(nameOrAddress)) return nameOrAddress.lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(nameOrAddress.toByteArray())
        return "0x" + digest.take(20).joinToString("") { "%02x".format(it) }
    }
}
