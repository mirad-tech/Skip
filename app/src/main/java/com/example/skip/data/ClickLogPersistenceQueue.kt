package com.example.skip.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ClickLogPersistenceQueue(
    private val scope: CoroutineScope,
    private val mutex: Mutex = Mutex()
) {
    fun enqueue(operation: suspend () -> Unit): Job {
        return scope.launch {
            mutex.withLock {
                operation()
            }
        }
    }
}
