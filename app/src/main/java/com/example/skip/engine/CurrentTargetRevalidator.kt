package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

internal object CurrentTargetRevalidator {
    private const val MAX_TARGET_DRIFT_PX = 48

    fun revalidateAtPoint(
        root: AccessibilityNodeInfo?,
        expectedPackageName: String,
        currentPackageName: String,
        x: Int,
        y: Int,
        originalTarget: ClickTargetInfo,
        activeTextInput: Boolean
    ): CurrentTargetRevalidation {
        return evaluate(
            rootAvailable = root != null,
            expectedPackageName = expectedPackageName,
            currentPackageName = currentPackageName,
            activeTextInput = activeTextInput,
            pageSafetyTexts = pageSafetyTexts(root),
            originalTarget = originalTarget,
            currentTarget = snapshotAtPoint(root, x, y)
        )
    }

    fun evaluate(
        rootAvailable: Boolean,
        expectedPackageName: String,
        currentPackageName: String,
        activeTextInput: Boolean,
        pageSafetyTexts: List<String>,
        originalTarget: ClickTargetInfo,
        currentTarget: CoordinateFallbackTargetSnapshot?
    ): CurrentTargetRevalidation {
        if (!rootAvailable) return CurrentTargetRevalidation.blocked("current_target_root_missing")
        if (currentPackageName != expectedPackageName) {
            return CurrentTargetRevalidation.blocked("current_target_package_changed")
        }
        if (activeTextInput) {
            return CurrentTargetRevalidation.blocked("current_target_active_text_input")
        }
        if (pageSafetyTexts.any(SafetyGuard::isSensitiveText)) {
            return CurrentTargetRevalidation.blocked("current_target_page_unsafe")
        }
        val snapshot = currentTarget
            ?: return CurrentTargetRevalidation.blocked("current_target_missing")
        if (snapshot.packageName.isBlank() || snapshot.packageName != expectedPackageName) {
            return CurrentTargetRevalidation.blocked("current_target_package_mismatch")
        }
        if (snapshot.hasUnsafeActionNode) {
            return CurrentTargetRevalidation.blocked("current_target_unsafe")
        }
        if (SafetyGuard.isProtectedPackage(snapshot.packageName) ||
            !ClickExecutor.hasCoordinateFallbackIdentity(snapshot.target) ||
            ClickExecutor.coordinateFallbackGestureTargetBlockReason(
                target = snapshot.target,
                hasClickableNodeOrAncestor = snapshot.hasClickableNodeOrAncestor
            ) != null ||
            !HighRiskClickPolicy.evaluateTexts(
                listOf(
                    snapshot.target.text,
                    snapshot.target.contentDescription,
                    snapshot.target.viewId,
                    snapshot.target.className,
                    snapshot.packageName
                ) + snapshot.ancestorSafetyTexts
            ).allowed
        ) {
            return CurrentTargetRevalidation.blocked("current_target_unsafe")
        }
        if (!snapshot.target.matchesOriginalTarget(originalTarget)) {
            return CurrentTargetRevalidation.blocked("current_target_changed")
        }
        return CurrentTargetRevalidation(
            allowed = true,
            reason = "current_target_revalidated",
            snapshot = snapshot
        )
    }

    fun snapshotAtPoint(
        root: AccessibilityNodeInfo?,
        x: Int,
        y: Int
    ): CoordinateFallbackTargetSnapshot? {
        if (root == null) return null
        return selectBestCandidateFromTree(
            root = root,
            childrenOf = { node ->
                buildList {
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(::add)
                    }
                }
            },
            candidateOf = snapshotCandidate@ { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!node.isVisibleToUser || !bounds.containsForPolicy(x, y)) {
                    return@snapshotCandidate null
                }
                val resolution = ClickExecutor.resolveCandidate(node)
                val action = resolution.relaxedSelection ?: return@snapshotCandidate null
                val actionPath = resolution.actionPathFor(action)
                val target = ClickExecutor.targetWithActionIdentity(
                    candidate = resolution.candidate,
                    actionTarget = action.target
                )
                if (!ClickExecutor.hasCoordinateFallbackIdentity(target)) {
                    return@snapshotCandidate null
                }
                CoordinateFallbackTargetSnapshot(
                    target = target,
                    packageName = node.packageName?.toString().orEmpty(),
                    ancestorSafetyTexts = resolution.ancestorSafetyTexts,
                    hasClickableNodeOrAncestor = true,
                    actionParentDepth = actionPath.parentDepth,
                    hasUnsafeActionNode = actionPath.hasUnsafeNode
                )
            },
            isBetter = { candidate, currentBest ->
                candidate.target.bounds.area() < currentBest.target.bounds.area()
            }
        )
    }

    internal fun <Node, Candidate> selectBestCandidateFromTree(
        root: Node,
        childrenOf: (Node) -> Iterable<Node>,
        candidateOf: (Node) -> Candidate?,
        isBetter: (Candidate, Candidate) -> Boolean
    ): Candidate? {
        val queue = ArrayDeque<Node>()
        queue.add(root)
        var best: Candidate? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val candidate = candidateOf(node)
            val currentBest = best
            if (candidate != null && (currentBest == null || isBetter(candidate, currentBest))) {
                best = candidate
            }
            childrenOf(node).forEach(queue::add)
        }
        return best
    }

    fun pageSafetyTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val values = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            values += listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                node.className?.toString().orEmpty()
            )
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return values
    }

    private fun ClickTargetInfo.matchesOriginalTarget(original: ClickTargetInfo): Boolean {
        if (!bounds.isNearCurrentTarget(original.bounds)) return false
        if (className.isNotBlank() && original.className.isNotBlank() && className != original.className) {
            return false
        }
        if (viewId.isNotBlank() || original.viewId.isNotBlank()) return viewId == original.viewId
        if (text.isNotBlank() || original.text.isNotBlank()) return text == original.text
        if (contentDescription.isNotBlank() || original.contentDescription.isNotBlank()) {
            return contentDescription == original.contentDescription
        }
        return className.isNotBlank() && className == original.className
    }

    private fun Rect.isNearCurrentTarget(other: Rect): Boolean {
        return abs(left - other.left) <= MAX_TARGET_DRIFT_PX &&
            abs(top - other.top) <= MAX_TARGET_DRIFT_PX &&
            abs(right - other.right) <= MAX_TARGET_DRIFT_PX &&
            abs(bottom - other.bottom) <= MAX_TARGET_DRIFT_PX
    }

    private fun Rect.containsForPolicy(x: Int, y: Int): Boolean {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom
    }

    private fun Rect.area(): Int {
        return (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    }
}

internal data class CurrentTargetRevalidation(
    val allowed: Boolean,
    val reason: String,
    val snapshot: CoordinateFallbackTargetSnapshot? = null
) {
    val target: ClickTargetInfo?
        get() = snapshot?.target

    companion object {
        fun blocked(reason: String): CurrentTargetRevalidation {
            return CurrentTargetRevalidation(allowed = false, reason = reason)
        }
    }
}
