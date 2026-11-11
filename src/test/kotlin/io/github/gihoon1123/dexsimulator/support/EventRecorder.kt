package io.github.gihoon1123.dexsimulator.support

import io.github.gihoon1123.dexsimulator.common.event.DomainEvent
import io.github.gihoon1123.dexsimulator.common.event.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/** Records every event published on [bus] from the moment [start] returns. */
class EventRecorder(
    private val bus: EventBus,
) {
    val events = CopyOnWriteArrayList<DomainEvent>()
    private var job: Job? = null

    suspend fun start(): EventRecorder {
        val subscribed = CompletableDeferred<Unit>()
        job =
            bus.events
                .onSubscription { subscribed.complete(Unit) }
                .onEach { events += it }
                .launchIn(CoroutineScope(Dispatchers.Default))
        subscribed.await()
        return this
    }

    inline fun <reified T : DomainEvent> ofType(): List<T> = events.filterIsInstance<T>()

    /** Waits until [condition] holds, since delivery is asynchronous. */
    suspend fun awaitUntil(condition: (List<DomainEvent>) -> Boolean) {
        withTimeout(TIMEOUT_MILLIS) {
            while (!condition(events)) delay(POLL_MILLIS)
        }
    }

    fun stop() {
        job?.cancel()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2_000L
        const val POLL_MILLIS = 5L
    }
}
