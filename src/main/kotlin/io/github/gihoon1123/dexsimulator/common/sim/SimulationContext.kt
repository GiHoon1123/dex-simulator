package io.github.gihoon1123.dexsimulator.common.sim

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * Single writer for all simulation state (pools, mempool, chain).
 *
 * Every read and write of simulation state runs on one thread, so services keep plain
 * collections without locks and a multi-step change (swap -> pool -> events) is never
 * interleaved with another request. Periodic jobs are launched in [scope], which runs on
 * the same thread. See docs/decisions.md #4.
 */
@Component
class SimulationContext : AutoCloseable {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    val dispatcher = executor.asCoroutineDispatcher()

    /** Scope for long-running simulation jobs. A failing job does not cancel the others. */
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(THREAD_NAME))

    /** Runs [block] on the simulation thread. Re-entrant: no dispatch when already on it. */
    suspend fun <T> run(block: suspend CoroutineScope.() -> T): T = withContext(dispatcher, block)

    override fun close() {
        scope.cancel()
        dispatcher.close()
    }

    companion object {
        const val THREAD_NAME = "simulation"
    }
}
