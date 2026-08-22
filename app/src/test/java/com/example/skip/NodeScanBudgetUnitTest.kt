package com.example.skip

import com.example.skip.engine.NodeScanBudget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeScanBudgetUnitTest {
    @Test
    fun scanStopsAtFiveHundredVisitedNodes() {
        assertEquals(500, NodeScanBudget.MAX_VISITED_NODES)
        assertTrue(NodeScanBudget.canEnqueueChild(visitedCount = 0, queuedCount = 0))
        assertTrue(NodeScanBudget.canEnqueueChild(visitedCount = 499, queuedCount = 0))
        assertFalse(NodeScanBudget.canEnqueueChild(visitedCount = 500, queuedCount = 0))
        assertFalse(NodeScanBudget.canEnqueueChild(visitedCount = 400, queuedCount = 100))
    }

    @Test
    fun nodeScannerUsesSharedVisitBudget() {
        val scanner = listOf(
            File("app/src/main/java/com/example/skip/engine/NodeScanner.kt"),
            File("../app/src/main/java/com/example/skip/engine/NodeScanner.kt")
        ).first { it.exists() }.readText()

        assertTrue(scanner.contains("NodeScanBudget.canEnqueueChild"))
        assertTrue(scanner.contains("NodeScanBudget.MAX_VISITED_NODES"))
    }
}
