package com.example.skip.model

import android.view.accessibility.AccessibilityNodeInfo

data class MatchResult(
    val sourceNode: AccessibilityNodeInfo,
    val clickNode: AccessibilityNodeInfo,
    val ruleId: String,
    val ruleName: String,
    val score: Int
)
