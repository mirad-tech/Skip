package com.example.skip.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ClickLogPersistenceQueue(
    private val scope: CoroutineScope,
    private val mutex: Mutex = Mutex()
) {
    private val orderLock = Any()
    private var tail: Job = Job().apply { complete() }

    fun enqueue(operation: suspend () -> Unit): Job {
        return synchronized(orderLock) {
            val previous = tail
            scope.launch(start = CoroutineStart.LAZY) {
                previous.join()
                mutex.withLock {
                    operation()
                }
            }.also { job ->
                tail = job
                job.start()
            }
        }
    }

    fun enqueueCatching(operation: suspend () -> Unit): Deferred<Result<Unit>> {
        val result = CompletableDeferred<Result<Unit>>()
        val job = enqueue {
            result.complete(runCatching { operation() })
        }
        job.invokeOnCompletion { error ->
            if (!result.isCompleted && error != null) {
                result.complete(Result.failure(error))
            }
        }
        return result
    }

    suspend fun <T> execute(operation: suspend () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        val job = enqueue { result.complete(runCatching { operation() }) }
        job.invokeOnCompletion { error ->
            if (!result.isCompleted && error != null) result.complete(Result.failure(error))
        }
        job.join()
        return result.await().getOrThrow()
    }
}
