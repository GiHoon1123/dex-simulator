package io.github.gihoon1123.dexsimulator.common.error

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** Ported from the original `http-exception.filter.spec.ts` (8 cases). */
class GlobalExceptionHandlerTest {
    private val fixedNow = Instant.parse("2025-10-17T04:55:05.123Z")
    private val handler = GlobalExceptionHandler(Clock.fixed(fixedNow, ZoneOffset.UTC))
    private val request = MockHttpServletRequest("GET", "/test-url")

    private fun expected(
        status: HttpStatus,
        message: String,
    ) = ErrorBody(
        statusCode = status.value(),
        timestamp = "2025-10-17T04:55:05.123Z",
        path = "/test-url",
        method = "GET",
        message = message,
    )

    @Test
    fun `api exception keeps its status and message`() {
        val response = handler.handle(ApiException(HttpStatus.BAD_REQUEST, "테스트 에러 메시지"), request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(expected(HttpStatus.BAD_REQUEST, "테스트 에러 메시지"), response.body)
    }

    @Test
    fun `plain exception is treated as bad request`() {
        val response = handler.handle(IllegalStateException("일반 에러 메시지"), request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(expected(HttpStatus.BAD_REQUEST, "일반 에러 메시지"), response.body)
    }

    @Test
    fun `non exception throwable is treated as internal server error`() {
        // The original also covered `null` and `undefined` throws; the closest JVM case is a
        // Throwable that is not an Exception (e.g. an Error).
        val response = handler.handle(StackOverflowError(), request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(expected(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"), response.body)
    }

    @Test
    fun `timestamp is an ISO-8601 instant in UTC`() {
        val response = handler.handle(ApiException(HttpStatus.NOT_FOUND, "없음"), request)

        assertEquals("2025-10-17T04:55:05.123Z", response.body?.timestamp)
    }

    @Test
    fun `various http statuses are passed through`() {
        val statuses =
            listOf(
                HttpStatus.OK,
                HttpStatus.CREATED,
                HttpStatus.NO_CONTENT,
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND,
                HttpStatus.CONFLICT,
                HttpStatus.UNPROCESSABLE_CONTENT,
                HttpStatus.TOO_MANY_REQUESTS,
            )

        statuses.forEach { status ->
            val response = handler.handle(ApiException(status, "상태 코드 ${status.value()} 테스트"), request)

            assertEquals(status, response.statusCode)
            assertEquals(expected(status, "상태 코드 ${status.value()} 테스트"), response.body)
        }
    }

    @Test
    fun `exception without message yields empty message`() {
        val response = handler.handle(RuntimeException(), request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(expected(HttpStatus.BAD_REQUEST, ""), response.body)
    }

    @Test
    fun `path keeps the query string like nest request url`() {
        val withQuery = MockHttpServletRequest("GET", "/test-url").apply { queryString = "a=1" }

        val response = handler.handle(ApiException(HttpStatus.BAD_REQUEST, "x"), withQuery)

        assertEquals("/test-url?a=1", response.body?.path)
    }
}
