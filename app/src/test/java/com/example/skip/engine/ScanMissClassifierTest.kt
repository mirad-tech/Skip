package com.example.skip.engine

import com.example.skip.model.ClickLogStage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the classification that decides whether an opening-ad recovery session survives a scan miss.
 *
 * `ClickLogStage.SkippedBySafety` makes the accessibility service call
 * `terminateOpeningAdRecovery()`, which drops every remaining rescan of the rule window. Heuristic
 * "this frame did not match" reasons must therefore never land in the safety bucket, and genuinely
 * unsafe reasons must never leave it.
 */
class ScanMissClassifierTest {

    private val recoverable = listOf(
        "ambiguous_candidate_large_bounds",
        "generic_skip_context_missing",
        "precise_area_mismatch",
        "precise_view_id_required",
        "precise_candidate_evidence_missing",
        "standalone_skip_not_top_right",
        "standalone_skip_candidate_too_large",
        "standalone_skip_label_not_exact"
    )

    private val terminalSafety = listOf(
        "blocked_by_safety_policy",
        "sensitive_skip_semantic",
        "text_input_clear_button",
        "activity_scope_mismatch",
        "generic_close_without_ad_context",
        "no_safe_clickable_target",
        "standalone_skip_unsafe_ancestor",
        "standalone_skip_candidate_unsafe",
        "standalone_skip_no_safe_action_path",
        "standalone_skip_rule_source_forbidden",
        "standalone_skip_forbidden",
        "standalone_skip_window_expired"
    )

    @Test
    fun recoverableReasonsDoNotTerminateTheSession() {
        recoverable.forEach { reason ->
            val stage = ScanMissClassifier.classify(reason, candidateCount = 1)
            assertEquals(
                "$reason must be a recoverable miss, not a safety block",
                ClickLogStage.NoCandidateFound,
                stage
            )
        }
    }

    @Test
    fun safetyReasonsStayTerminalEvenWithoutCandidates() {
        // A safety rejection is frequently reported with candidateCount == 0 (the rejected candidate
        // is not counted as a usable one). Safety must win over the "no candidate" branch, otherwise
        // the session would keep rescanning a page the safety policy already refused.
        terminalSafety.forEach { reason ->
            assertEquals(
                "$reason must stay a safety block",
                ClickLogStage.SkippedBySafety,
                ScanMissClassifier.classify(reason, candidateCount = 0)
            )
            assertEquals(
                "$reason must stay a safety block",
                ClickLogStage.SkippedBySafety,
                ScanMissClassifier.classify(reason, candidateCount = 3)
            )
        }
    }

    @Test
    fun emptyCandidateListIsAPlainMiss() {
        assertEquals(
            ClickLogStage.NoCandidateFound,
            ScanMissClassifier.classify("no_candidate_found", candidateCount = 0)
        )
        assertEquals(
            ClickLogStage.NoCandidateFound,
            ScanMissClassifier.classify("root_window_null", candidateCount = 0)
        )
    }

    @Test
    fun lowScoreIsReportedAsLowScore() {
        listOf("score_below_min_score", "candidate_below_threshold").forEach { reason ->
            assertEquals(
                ClickLogStage.SkippedByLowScore,
                ScanMissClassifier.classify(reason, candidateCount = 2)
            )
        }
    }

    @Test
    fun unknownReasonFallsBackToSafety() {
        // Fail closed: a new, unclassified reason must not silently keep clicking.
        assertEquals(
            ClickLogStage.SkippedBySafety,
            ScanMissClassifier.classify("some_future_reason", candidateCount = 1)
        )
    }

    @Test
    fun recoverableAndTerminalSetsDoNotOverlap() {
        val overlap = ScanMissClassifier.RECOVERABLE_MISS_REASONS
            .intersect(ScanMissClassifier.TERMINAL_SAFETY_REASONS)
        assertEquals("a reason cannot be both recoverable and terminal", emptySet<String>(), overlap)
    }
}
