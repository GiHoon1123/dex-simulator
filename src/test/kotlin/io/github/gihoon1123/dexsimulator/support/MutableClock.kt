package io.github.gihoon1123.dexsimulator.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Test clock that only moves when [advance] is called. */
class MutableClock(
    @Volatile private var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
