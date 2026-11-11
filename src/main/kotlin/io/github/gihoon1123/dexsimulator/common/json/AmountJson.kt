package io.github.gihoon1123.dexsimulator.common.json

import org.springframework.boot.jackson.JacksonComponent
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import java.math.BigDecimal

/**
 * Writes amounts as plain JSON numbers without trailing zeros.
 *
 * Amounts are kept at the token's full scale (e.g. 18 digits for ETH), so without this
 * `1000` would be written as `1000.000000000000000000` or in exponent form.
 */
@JacksonComponent
class AmountJson {
    class Serializer : ValueSerializer<BigDecimal>() {
        override fun serialize(
            value: BigDecimal,
            gen: JsonGenerator,
            ctxt: SerializationContext,
        ) {
            gen.writeNumber(value.stripTrailingZeros().toPlainString())
        }
    }
}
