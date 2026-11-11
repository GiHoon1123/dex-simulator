package io.github.gihoon1123.dexsimulator.common.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/** Common shape of every simulation event. [type] keeps the original event name. */
interface DomainEvent {
    val eventId: String
    val type: String
    val timestamp: Instant
}

/**
 * In-process event bus on a [SharedFlow], replacing NestJS `EventEmitter2`.
 *
 * Differences from the original (docs/decisions.md #5):
 * - Delivery is asynchronous. Handlers run after the publisher suspends or returns, in
 *   publish order. The original `emit` called listeners synchronously.
 * - Events published while nobody is subscribed are dropped (no replay), so subscribers
 *   that need existing state read it in `onSubscribed`.
 */
@Component
class EventBus {
    private val flow = MutableSharedFlow<DomainEvent>(extraBufferCapacity = BUFFER_CAPACITY)

    val events: SharedFlow<DomainEvent> = flow.asSharedFlow()

    /** Suspends only when the buffer is full, so slow subscribers apply back-pressure. */
    suspend fun publish(event: DomainEvent) = flow.emit(event)

    /**
     * Subscribes to events of type [type] in [scope].
     *
     * [onSubscribed] runs once the subscription is active, so nothing published after it
     * is missed. A failing handler is logged and does not stop the subscription.
     */
    fun <T : DomainEvent> subscribe(
        type: KClass<T>,
        scope: CoroutineScope,
        onSubscribed: suspend () -> Unit = {},
        handler: suspend (T) -> Unit,
    ): Job =
        events
            .onSubscription { onSubscribed() }
            .filterIsInstance(type)
            .onEach { event ->
                try {
                    handler(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Event handler failed: {}", event.type, e)
                }
            }.launchIn(scope)

    final inline fun <reified T : DomainEvent> subscribe(
        scope: CoroutineScope,
        noinline onSubscribed: suspend () -> Unit = {},
        noinline handler: suspend (T) -> Unit,
    ): Job = subscribe(T::class, scope, onSubscribed, handler)

    companion object {
        const val BUFFER_CAPACITY = 1024

        private val log = LoggerFactory.getLogger(EventBus::class.java)
    }
}

/** Builds ids in the original format: `event_{epochMillis}_{9 random base36 chars}`. */
object EventIds {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val RANDOM_LENGTH = 9

    fun next(now: Instant): String {
        val random = (1..RANDOM_LENGTH).map { ALPHABET.random() }.joinToString("")
        return "event_${now.toEpochMilli()}_$random"
    }
}
