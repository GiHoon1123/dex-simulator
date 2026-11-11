package io.github.gihoon1123.dexsimulator.common.error

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Error body with the same fields as the original NestJS `HttpExceptionFilter`. */
data class ErrorBody(
    val statusCode: Int,
    val timestamp: String,
    val path: String,
    val method: String,
    val message: String,
)

/**
 * Global error handler ported from the original `HttpExceptionFilter`.
 *
 * Mapping (kept identical to the original):
 * - [ApiException] or Spring's [ErrorResponse] exceptions: their own status and message
 * - any other [Exception]: 400 with the exception message (empty string when absent)
 * - anything else ([Error] such as OutOfMemoryError): 500 "Internal server error"
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val clock: Clock = Clock.systemUTC(),
) {
    @ExceptionHandler(Throwable::class)
    fun handle(
        exception: Throwable,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorBody> {
        val (status, message) = resolve(exception)
        val body =
            ErrorBody(
                statusCode = status.value(),
                timestamp = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString(),
                path = request.pathWithQuery(),
                method = request.method,
                message = message,
            )
        return ResponseEntity.status(status).body(body)
    }

    internal fun resolve(exception: Throwable): Pair<HttpStatusCode, String> =
        when (exception) {
            is ApiException -> exception.status to exception.message
            // Field errors of @Valid bodies and query objects, e.g. "amountIn: must be ...".
            is BindException -> HttpStatus.BAD_REQUEST to exception.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
            is ErrorResponse -> exception.statusCode to (exception.body.detail ?: exception.message ?: "")
            is Exception -> HttpStatus.BAD_REQUEST to (exception.message ?: "")
            else -> HttpStatus.INTERNAL_SERVER_ERROR to INTERNAL_ERROR_MESSAGE
        }

    // NestJS `request.url` includes the query string, so keep it for parity.
    private fun HttpServletRequest.pathWithQuery(): String = if (queryString.isNullOrEmpty()) requestURI else "$requestURI?$queryString"

    companion object {
        const val INTERNAL_ERROR_MESSAGE = "Internal server error"
    }
}
