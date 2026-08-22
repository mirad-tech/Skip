package com.example.skip.engine

internal object NodeScanBudget {
    const val MAX_VISITED_NODES = 500

    fun canEnqueueChild(visitedCount: Int, queuedCount: Int): Boolean {
        return visitedCount + queuedCount < MAX_VISITED_NODES
    }
}
