package com.example.skip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the node-scan budget accounting.
 *
 * `MAX_VISITED_NODES` is enforced by [NodeScanBudget.canEnqueueChild] (it keeps `visited + queued`
 * at or below the cap), not by a `visitedCount < MAX_VISITED_NODES` loop guard. The scanner used to
 * decide "the tree was truncated" by comparing the final visit count with the cap, which reported
 * `scan_budget_exhausted` for a tree of exactly 500 nodes that had been explored in full, and
 * reported a plain miss for a tree that really was cut short.
 *
 * These tests exercise the traversal helpers with a synthetic tree, so they need no Android types.
 */
class NodeScanBudgetTest {

    private fun treeChildCount(totalNodes: Int): (Int) -> Int = { node ->
        val firstChild = node * 2 + 1
        if (firstChild >= totalNodes) 0 else minOf(2, totalNodes - firstChild)
    }

    private fun treeChildAt(totalNodes: Int): (Int, Int) -> Int? = { node, index ->
        val child = node * 2 + 1 + index
        if (child >= totalNodes) null else child
    }

    @Test
    fun enqueueGuardKeepsVisitedPlusQueuedWithinBudget() {
        // The guard is `visited + queued < MAX_VISITED_NODES`, so the sum must stay strictly below
        // the cap for another child to be admissible.
        assertTrue(NodeScanBudget.canEnqueueChild(visitedCount = 1, queuedCount = 1))
        assertFalse(
            NodeScanBudget.canEnqueueChild(
                visitedCount = 1,
                queuedCount = NodeScanBudget.MAX_VISITED_NODES - 1
            )
        )
        assertFalse(
            NodeScanBudget.canEnqueueChild(
                visitedCount = NodeScanBudget.MAX_VISITED_NODES,
                queuedCount = 0
            )
        )
        assertTrue(
            NodeScanBudget.canEnqueueChild(
                visitedCount = NodeScanBudget.MAX_VISITED_NODES - 2,
                queuedCount = 0
            )
        )
    }

    @Test
    fun smallTreeIsTraversedCompletely() {
        val totalNodes = 200
        var visited = 0
        val result = NodeScanBudget.walkDepthFirst(
            root = 0,
            childCountOf = treeChildCount(totalNodes),
            childAt = treeChildAt(totalNodes),
            visit = { _, _ -> visited++ }
        )
        assertEquals(NodeTraversalResult.Complete, result)
        assertEquals(totalNodes, visited)
    }

    @Test
    fun walkDepthFirstIsBoundedByPathAndTimeNotByVisitCount() {
        // `walkDepthFirst` streams wide trees and retains only the current path, so it has no visit
        // cap: a broad tree larger than MAX_VISITED_NODES is still traversed in full. Its bounds are
        // MAX_RETAINED_PATH_NODES (see the deep-chain test) and MAX_TARGET_SCAN_NANOS. Callers that
        // need a visit cap must use `walk` / `canEnqueueChild` instead.
        val totalNodes = NodeScanBudget.MAX_VISITED_NODES * 3
        var visited = 0
        val result = NodeScanBudget.walkDepthFirst(
            root = 0,
            childCountOf = treeChildCount(totalNodes),
            childAt = treeChildAt(totalNodes),
            visit = { _, _ -> visited++ }
        )
        assertEquals(NodeTraversalResult.Complete, result)
        assertEquals(totalNodes, visited)
    }

    @Test
    fun expiredTimeBudgetStopsTheWalkImmediately() {
        val start = System.nanoTime() - NodeScanBudget.MAX_TARGET_SCAN_NANOS - 1_000_000L
        var visited = 0
        val result = NodeScanBudget.walkDepthFirst(
            root = 0,
            childCountOf = treeChildCount(1_000),
            childAt = treeChildAt(1_000),
            visit = { _, _ -> visited++ },
            scanStartedAtNanos = start
        )
        assertEquals(NodeTraversalResult.Exhausted, result)
        assertTrue("an expired budget must not walk far: visited=$visited", visited <= 2)
    }

    @Test
    fun deepChainIsBoundedByRetainedPath() {
        // A degenerate chain (every node has exactly one child) grows the retained path only, which is
        // the other half of the budget: it must stop rather than retain the whole chain.
        val totalNodes = NodeScanBudget.MAX_RETAINED_PATH_NODES * 3
        var visited = 0
        val result = NodeScanBudget.walkDepthFirst(
            root = 0,
            childCountOf = { node -> if (node + 1 < totalNodes) 1 else 0 },
            childAt = { node, _ -> node + 1 },
            visit = { _, _ -> visited++ }
        )
        assertEquals(NodeTraversalResult.Exhausted, result)
        assertTrue(
            "retained path must bound the walk: visited=$visited",
            visited <= NodeScanBudget.MAX_RETAINED_PATH_NODES + 1
        )
    }

    @Test
    fun timeBudgetStartsUnarmedAndExpires() {
        assertFalse(NodeScanBudget.isTimeExpired(null))
        assertFalse(NodeScanBudget.isTimeExpired(System.nanoTime()))
        assertTrue(
            NodeScanBudget.isTimeExpired(
                System.nanoTime() - NodeScanBudget.MAX_TARGET_SCAN_NANOS - 1_000_000L
            )
        )
    }

    @Test
    fun unavailableChildrenAreReportedAsUnavailable() {
        val result = NodeScanBudget.walkDepthFirst(
            root = 0,
            childCountOf = { if (it == 0) 3 else 0 },
            childAt = { _, index -> if (index == 1) null else index + 10 },
            visit = { _, _ -> }
        )
        assertEquals(NodeTraversalResult.Unavailable, result)
    }
}
