package com.example.skip

import com.example.skip.engine.ClickExecutor
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.RuleCandidateSignals
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePipelineUnitTest {
    @Test
    fun containsModePrefersExactSpecificKeywordOverGenericSubstring() {
        assertEquals(
            "跳过广告",
            ScoreEvaluator.matchedTextRule(
                values = listOf("跳过广告"),
                keywords = listOf("跳过", "跳过广告"),
                mode = MatchMode.Contains
            )
        )
    }

    @Test
    fun ruleSignalPrefilterMatchesTextDescriptionAndViewIdWithExistingModes() {
        val textRule = rule(
            matchTexts = listOf("跳过广告"),
            textMatchMode = MatchMode.Contains
        )
        val descriptionRule = rule(
            matchContentDescriptions = listOf("Skip Ad"),
            contentDescriptionMatchMode = MatchMode.Exact
        )
        val viewIdRule = rule(
            matchViewIds = listOf("splash_skip"),
            viewIdMatchMode = MatchMode.Contains
        )

        assertTrue(
            ScoreEvaluator.hasPotentialRuleMatch(
                signals(text = "3 秒后跳过广告"),
                textRule
            )
        )
        assertTrue(
            ScoreEvaluator.hasPotentialRuleMatch(
                signals(contentDescription = "Skip Ad"),
                descriptionRule
            )
        )
        assertTrue(
            ScoreEvaluator.hasPotentialRuleMatch(
                signals(viewId = "com.example:id/splash_skip_button"),
                viewIdRule
            )
        )
    }

    @Test
    fun ruleSignalPrefilterRejectsUnrelatedNodeWithoutCreatingGenericMatch() {
        val rule = rule(
            matchTexts = listOf("跳过广告"),
            matchContentDescriptions = listOf("Skip Ad"),
            matchViewIds = listOf("splash_skip")
        )

        assertFalse(
            ScoreEvaluator.hasPotentialRuleMatch(
                signals(
                    text = "关闭",
                    contentDescription = "关闭弹幕",
                    viewId = "com.example:id/close_button"
                ),
                rule
            )
        )
    }

    @Test
    fun parentChainWalkerReadsEachVisitedParentExactlyOnce() {
        val depth3 = ChainNode("depth3")
        val depth2 = ChainNode("depth2", depth3)
        val depth1 = ChainNode("depth1", depth2)
        val depth0 = ChainNode("depth0", depth1)
        val parentReads = linkedMapOf<String, Int>()
        val visits = mutableListOf<String>()

        ClickExecutor.walkParentChain(
            start = depth0,
            maxDepth = 2,
            parentOf = { node ->
                parentReads[node.name] = (parentReads[node.name] ?: 0) + 1
                node.parent
            }
        ) { node, parent, depth ->
            visits += "${node.name}->${parent?.name}:$depth"
        }

        assertEquals(
            listOf(
                "depth0->depth1:0",
                "depth1->depth2:1",
                "depth2->depth3:2"
            ),
            visits
        )
        assertEquals(mapOf("depth0" to 1, "depth1" to 1, "depth2" to 1), parentReads)
    }

    @Test
    fun scanFailureReasonComesFromBestCandidate() {
        assertEquals(
            "best_candidate_reason",
            NodeScanner.failureReasonForScan(
                candidateCount = 3,
                bestMatchFound = false,
                bestCandidateFailureReason = "best_candidate_reason"
            )
        )
        assertEquals(
            "candidate_below_threshold",
            NodeScanner.failureReasonForScan(
                candidateCount = 1,
                bestMatchFound = false,
                bestCandidateFailureReason = ""
            )
        )
        assertEquals(
            "no_candidate_found",
            NodeScanner.failureReasonForScan(
                candidateCount = 0,
                bestMatchFound = false,
                bestCandidateFailureReason = "ignored"
            )
        )
        assertEquals(
            "",
            NodeScanner.failureReasonForScan(
                candidateCount = 2,
                bestMatchFound = true,
                bestCandidateFailureReason = "ignored"
            )
        )
    }

    private fun signals(
        text: String = "",
        contentDescription: String = "",
        viewId: String = ""
    ): RuleCandidateSignals {
        return RuleCandidateSignals(
            text = text,
            contentDescription = contentDescription,
            viewId = viewId,
            className = "android.widget.TextView",
            input = false
        )
    }

    private fun rule(
        matchTexts: List<String> = emptyList(),
        matchContentDescriptions: List<String> = emptyList(),
        matchViewIds: List<String> = emptyList(),
        textMatchMode: MatchMode = MatchMode.Contains,
        contentDescriptionMatchMode: MatchMode = MatchMode.Contains,
        viewIdMatchMode: MatchMode = MatchMode.Contains
    ): SkipRule {
        return SkipRule(
            id = "pipeline_test",
            source = RuleSource.BuiltIn,
            name = "pipeline test",
            packageName = "com.example.target",
            appName = "Target",
            matchTexts = matchTexts,
            matchContentDescriptions = matchContentDescriptions,
            matchViewIds = matchViewIds,
            textMatchMode = textMatchMode,
            contentDescriptionMatchMode = contentDescriptionMatchMode,
            viewIdMatchMode = viewIdMatchMode,
            createdAt = 1L
        )
    }

    private data class ChainNode(
        val name: String,
        val parent: ChainNode? = null
    )
}
