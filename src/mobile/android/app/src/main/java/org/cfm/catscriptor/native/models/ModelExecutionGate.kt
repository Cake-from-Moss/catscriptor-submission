package org.cfm.catscriptor.native.models

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Один тяжёлый владелец моделей в процессе; финальный pipeline отзывает live-сеанс. */
internal object ModelExecutionGate {
    private val mutex = Mutex()
    private val lock = Any()
    private var live: Job? = null
    private var finalRequests = 0

    suspend fun preview(job: Job, block: suspend () -> Unit): Boolean {
        val acquired = synchronized(lock) {
            if (finalRequests > 0 || !mutex.tryLock()) false else { live = job; true }
        }
        if (!acquired) return false
        try { block() }
        finally {
            synchronized(lock) { if (live === job) live = null; mutex.unlock() }
        }
        return true
    }

    suspend fun <T> finalPipeline(block: suspend () -> T): T {
        val previous = synchronized(lock) { finalRequests++; live }
        try {
            previous?.cancelAndJoin()
            return mutex.withLock { block() }
        } finally { synchronized(lock) { finalRequests-- } }
    }
}
