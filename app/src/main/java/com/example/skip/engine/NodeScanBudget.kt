package com.example.skip.engine

internal object NodeScanBudget {
    const val MAX_VISITED_NODES = 500
    const val MAX_RETAINED_PATH_NODES = 500
    const val MAX_TARGET_SCAN_NANOS = 300_000_000L

    fun isTimeExpired(startedAtNanos: Long?): Boolean {
        return startedAtNanos != null &&
            System.nanoTime() - startedAtNanos >= MAX_TARGET_SCAN_NANOS
    }

    fun canEnqueueChild(visitedCount: Int, queuedCount: Int): Boolean {
        return visitedCount + queuedCount < MAX_VISITED_NODES
    }

    /** Bounds both retained nodes and child reads, including reads that return null. */
    fun <Node : Any> walk(
        root: Node,
        childCountOf: (Node) -> Int,
        childAt: (Node, Int) -> Node?,
        stopWhen: (Node) -> Boolean
    ): NodeTraversalResult {
        val queue = ArrayDeque<Node>()
        queue.add(root)
        var acquiredCount = 1 // The caller already acquired the root.
        var exhausted = false
        var unavailable = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (stopWhen(node)) return NodeTraversalResult.Stopped
            val childCount = childCountOf(node)
            for (index in 0 until childCount) {
                if (acquiredCount >= MAX_VISITED_NODES) {
                    exhausted = true
                    break
                }
                acquiredCount++
                val child = childAt(node, index)
                if (child == null) unavailable = true else queue.add(child)
            }
        }
        return when {
            exhausted -> NodeTraversalResult.Exhausted
            unavailable -> NodeTraversalResult.Unavailable
            else -> NodeTraversalResult.Complete
        }
    }

    /** Streams wide trees while retaining only the current path, never a whole level. */
    fun <Node : Any> walkDepthFirst(
        root: Node,
        childCountOf: (Node) -> Int,
        childAt: (Node, Int) -> Node?,
        visit: (Node, Int) -> Unit,
        nanoTime: () -> Long = System::nanoTime,
        scanStartedAtNanos: Long? = null
    ): NodeTraversalResult {
        val startedAt = scanStartedAtNanos ?: nanoTime()
        if (nanoTime() - startedAt >= MAX_TARGET_SCAN_NANOS) return NodeTraversalResult.Exhausted
        val path = ArrayDeque<TraversalFrame<Node>>()
        var unavailable = false
        visit(root, 0)
        path.add(TraversalFrame(root, childCountOf(root)))
        while (path.isNotEmpty()) {
            if (nanoTime() - startedAt >= MAX_TARGET_SCAN_NANOS) {
                return NodeTraversalResult.Exhausted
            }
            val frame = path.last()
            if (frame.nextChild >= frame.childCount) {
                path.removeLast()
                continue
            }
            if (path.size >= MAX_RETAINED_PATH_NODES) return NodeTraversalResult.Exhausted
            val child = childAt(frame.node, frame.nextChild++)
            if (nanoTime() - startedAt >= MAX_TARGET_SCAN_NANOS) {
                return NodeTraversalResult.Exhausted
            }
            if (child == null) {
                unavailable = true
                continue
            }
            visit(child, path.size)
            path.add(TraversalFrame(child, childCountOf(child)))
        }
        return if (unavailable) NodeTraversalResult.Unavailable else NodeTraversalResult.Complete
    }
}

private class TraversalFrame<Node>(val node: Node, val childCount: Int, var nextChild: Int = 0)

internal enum class NodeTraversalResult(val predicateMatched: Boolean?) {
    Complete(false),
    Stopped(true),
    Exhausted(null),
    Unavailable(null)
}
