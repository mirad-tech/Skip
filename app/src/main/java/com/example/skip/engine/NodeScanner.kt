package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchResult
import com.example.skip.model.SkipRule

object NodeScanner {
    fun findBestMatch(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        appElapsedMs: Long
    ): MatchResult? {
        if (rules.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var best: MatchResult? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            rules.forEach { rule ->
                val match = RuleMatcher.match(node, rule, appElapsedMs)
                if (match != null && (best == null || match.score > best.score)) {
                    best = match
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return best
    }
}
