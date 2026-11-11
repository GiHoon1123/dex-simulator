package io.github.gihoon1123.dexsimulator.common.sim

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Current time at millisecond precision, matching JavaScript `Date`. */
fun Clock.nowMillis(): Instant = Instant.now(this).truncatedTo(ChronoUnit.MILLIS)
