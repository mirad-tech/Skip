package com.example.skip.engine

import com.example.skip.model.ClickLogStage

/**
 * Maps a scan failure to the log stage that decides whether the opening-ad recovery session
 * continues.
 *
 * This lives in a plain object (no Android types on the signature) so the classification can be
 * unit-tested: getting it wrong is a functional regression, not a cosmetic one, because
 * [ClickLogStage.SkippedBySafety] makes the service call `terminateOpeningAdRecovery()` and drop the
 * remaining frames of the rule window.
 */
internal object ScanMissClassifier {
    const val BUDGET_EXHAUSTED = "scan_budget_exhausted"
    const val CANDIDATE_BELOW_THRESHOLD = "candidate_below_threshold"
    const val SCORE_BELOW_MIN = "score_below_min_score"

    /**
     * Scan failures that mean "this frame did not match", not "this frame is unsafe".
     *
     * Every reason here comes from a heuristic rejection — position, size, label exactness or
     * precise-rule evidence — whose candidate was already denied a click selection or a passing
     * score, so `RuleMatcher` cannot build a `MatchResult` from it. Classifying them as a plain miss
     * therefore cannot widen what is clickable.
     *
     * This matters most for the built-in default rule: `ScoreEvaluator` enters the standalone-skip
     * gate purely on `isStandaloneSkipLabel`, which ignores position, so a first frame whose exact
     * "跳过"/"skip" sits outside the top-right would otherwise mark the session terminal and never
     * let a later, correctly placed frame match.
     */
    val RECOVERABLE_MISS_REASONS = setOf(
        "ambiguous_candidate_large_bounds",
        "generic_skip_context_missing",
        "precise_area_mismatch",
        "precise_view_id_required",
        "precise_candidate_evidence_missing",
        "standalone_skip_not_top_right",
        "standalone_skip_candidate_too_large",
        "standalone_skip_label_not_exact"
    )

    /**
     * Scan failures that must stay terminal. Listed explicitly so the safety set is reviewable
     * against the recoverable set; anything not in either set falls through to
     * [ClickLogStage.SkippedBySafety].
     */
    val TERMINAL_SAFETY_REASONS = setOf(
        HighRiskClickPolicy.BLOCKED_REASON,
        "sensitive_skip_semantic",
        "text_input_clear_button",
        "activity_scope_mismatch",
        ScoreEvaluator.GENERIC_CLOSE_BLOCKED_REASON,
        "no_safe_clickable_target",
        "standalone_skip_unsafe_ancestor",
        "standalone_skip_candidate_unsafe",
        "standalone_skip_no_safe_action_path",
        "standalone_skip_rule_source_forbidden",
        "standalone_skip_forbidden",
        "standalone_skip_window_expired"
    )

    fun classify(failureReason: String, candidateCount: Int): ClickLogStage {
        return when {
            failureReason in TERMINAL_SAFETY_REASONS -> ClickLogStage.SkippedBySafety
            candidateCount == 0 -> ClickLogStage.NoCandidateFound
            failureReason == SCORE_BELOW_MIN || failureReason == CANDIDATE_BELOW_THRESHOLD ->
                ClickLogStage.SkippedByLowScore

            failureReason in RECOVERABLE_MISS_REASONS -> ClickLogStage.NoCandidateFound

            else -> ClickLogStage.SkippedBySafety
        }
    }
}
