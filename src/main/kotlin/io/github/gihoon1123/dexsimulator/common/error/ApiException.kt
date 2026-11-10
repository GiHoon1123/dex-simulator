package io.github.gihoon1123.dexsimulator.common.error

import org.springframework.http.HttpStatus

/**
 * Exception carrying an explicit HTTP status.
 * Counterpart of NestJS `HttpException` in the original project.
 */
open class ApiException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)
