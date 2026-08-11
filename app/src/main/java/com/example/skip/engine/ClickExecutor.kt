package com.example.skip.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.util.AccessibilityNodeAccess
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.util.PrivacySanitizer
import kotlin.math.abs

internal data class RuleCandidateSignals(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val input: Boolean
)

object ClickExecutor {
    const val COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON = "coordinate_text_input_clear_button"

    private const val MAX_CLICKABLE_PARENT_DEPTH = 4
    private const val MAX_CLICK_TARGET_SCREEN_RATIO = 0.35f
    private const val MAX_DEFAULT_TARGET_SCREEN_RATIO = 0.10f
    private const val MAX_DEFAULT_TARGET_WIDTH_RATIO = 0.40f
    private const val MAX_DEFAULT_TARGET_HEIGHT_RATIO = 0.20f
    private const val MIN_CLICK_TARGET_SIZE_PX = 8
    private const val BOUNDS_TOLERANCE_PX = 8

    fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findClickableSelection(node, defaultRule = false)?.node
    }

    internal fun resolveCandidate(
        node: AccessibilityNodeInfo,
        candidateSignals: RuleCandidateSignals = describeRuleCandidateSignals(node)
    ): ClickCandidateResolution {
        lateinit var candidate: ClickTargetInfo
        var strictSelection: ClickTargetSelection? = null
        var relaxedSelection: ClickTargetSelection? = null
        val ancestorSafetyTexts = mutableListOf<String>()
        val unsafeNodeDepths = mutableSetOf<Int>()

        walkParentChain(
            start = node,
            maxDepth = MAX_CLICKABLE_PARENT_DEPTH,
            parentOf = AccessibilityNodeAccess::parent
        ) { current, parent, depth ->
            val signals = if (depth == 0) {
                candidateSignals
            } else {
                describeRuleCandidateSignals(current)
            }
            val target = describeTarget(
                node = current,
                parentClickable = parent?.isClickable == true,
                signals = signals
            )
            if (depth == 0) candidate = target

            ancestorSafetyTexts += listOf(
                current.text?.toString().orEmpty(),
                current.contentDescription?.toString().orEmpty(),
                current.viewIdResourceName.orEmpty(),
                current.className?.toString().orEmpty()
            )
            if (current.isUnsafeActionPathNode()) unsafeNodeDepths += depth

            val relaxedSafe = current.isSafeClickTarget(defaultRule = false)
            if (relaxedSafe && relaxedSelection == null) {
                relaxedSelection = ClickTargetSelection(
                    node = current,
                    target = target,
                    parentDepth = depth,
                    source = depth.toClickTargetSource()
                )
            }
            if (relaxedSafe && !target.bounds.isLargeDefaultBounds() && strictSelection == null) {
                strictSelection = ClickTargetSelection(
                    node = current,
                    target = target,
                    parentDepth = depth,
                    source = depth.toClickTargetSource()
                )
            }
        }

        return ClickCandidateResolution(
            candidate = candidate,
            strictSelection = strictSelection,
            relaxedSelection = relaxedSelection,
            ancestorSafetyTexts = ancestorSafetyTexts,
            unsafeNodeDepths = unsafeNodeDepths
        )
    }

    fun targetWithActionIdentity(
        candidate: ClickTargetInfo,
        actionTarget: ClickTargetInfo?
    ): ClickTargetInfo {
        return candidate.copy(
            text = candidate.text.ifBlank { actionTarget?.text.orEmpty() },
            contentDescription = candidate.contentDescription.ifBlank {
                actionTarget?.contentDescription.orEmpty()
            },
            viewId = candidate.viewId.ifBlank { actionTarget?.viewId.orEmpty() },
            parentClickable = candidate.parentClickable || actionTarget != null
        )
    }

    fun findClickableSelection(
        node: AccessibilityNodeInfo,
        defaultRule: Boolean
    ): ClickTargetSelection? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null) {
            if (current.isSafeClickTarget(defaultRule)) {
                val info = describeTarget(current)
                return ClickTargetSelection(
                    node = current,
                    target = info,
                    parentDepth = depth,
                    source = if (depth == 0) {
                        ClickTargetSourceLog.NodeSelf
                    } else {
                        ClickTargetSourceLog.ClickableParent
                    }
                )
            }
            if (++depth > MAX_CLICKABLE_PARENT_DEPTH) return null
            current = AccessibilityNodeAccess.parent(current)
        }
        return null
    }

    fun isSelfSafeClickable(node: AccessibilityNodeInfo): Boolean {
        return node.isSafeClickTarget(defaultRule = false)
    }

    fun click(node: AccessibilityNodeInfo): ClickAttempt {
        val target = describeTarget(node)
        val accepted = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        return ClickAttempt(
            method = ClickMethodLog.ActionClick,
            accepted = accepted,
            target = target,
            reason = if (accepted) "action_click_returned_true" else "action_click_returned_false"
        )
    }

    fun gestureClick(
        service: AccessibilityService,
        target: ClickTargetInfo,
        allowLargeBounds: Boolean = false,
        onResult: (ClickAttempt) -> Unit
    ): Boolean {
        if (!target.canUseGestureFallback(allowLargeBounds)) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = "gesture_fallback_not_safe"
                )
            )
            return false
        }

        val centerX = target.bounds.exactCenterX()
        val centerY = target.bounds.exactCenterY()
        val path = Path().apply {
            moveTo(centerX, centerY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    onResult(
                        ClickAttempt(
                            method = ClickMethodLog.DispatchGesture,
                            accepted = true,
                            target = target,
                            reason = "gesture_completed"
                        )
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    onResult(
                        ClickAttempt(
                            method = ClickMethodLog.DispatchGesture,
                            accepted = false,
                            target = target,
                            reason = "gesture_cancelled"
                        )
                    )
                }
            },
            Handler(Looper.getMainLooper())
        )
        if (!accepted) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = "gesture_dispatch_returned_false"
                )
            )
        }
        return accepted
    }

    fun gestureClickPoint(
        service: AccessibilityService,
        target: ClickTargetInfo,
        x: Int,
        y: Int,
        onResult: (ClickAttempt) -> Unit
    ): Boolean {
        val blockReason = coordinateFallbackGestureTargetBlockReason(target)
        if (blockReason != null) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = blockReason
                )
            )
            return false
        }
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        if (x !in 0 until screenWidth || y !in 0 until screenHeight) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = "coordinate_fallback_out_of_screen"
                )
            )
            return false
        }

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    onResult(
                        ClickAttempt(
                            method = ClickMethodLog.DispatchGesture,
                            accepted = true,
                            target = target,
                            reason = "coordinate_fallback_completed"
                        )
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    onResult(
                        ClickAttempt(
                            method = ClickMethodLog.DispatchGesture,
                            accepted = false,
                            target = target,
                            reason = "coordinate_fallback_cancelled"
                        )
                    )
                }
            },
            Handler(Looper.getMainLooper())
        )
        if (!accepted) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = "coordinate_fallback_dispatch_returned_false"
                )
            )
        }
        return accepted
    }

    fun isCoordinateFallbackGestureTargetSafe(target: ClickTargetInfo): Boolean {
        return coordinateFallbackGestureTargetBlockReason(target) == null
    }

    fun coordinateFallbackGestureTargetBlockReason(
        target: ClickTargetInfo,
        hasClickableNodeOrAncestor: Boolean = false
    ): String? {
        if (TextInputClearButtonPolicy.shouldBlockRuleCandidate(
                viewId = target.viewId,
                text = target.text,
                contentDescription = target.contentDescription
            )
        ) {
            return COORDINATE_TEXT_INPUT_CLEAR_BUTTON_REASON
        }
        if (target.bounds.left >= target.bounds.right || target.bounds.top >= target.bounds.bottom ||
            target.input || target.password ||
            !target.enabled || !target.visibleToUser
        ) {
            return "coordinate_fallback_target_unsafe"
        }
        if (!target.nodeClickable && !target.parentClickable && !hasClickableNodeOrAncestor) {
            return "coordinate_fallback_target_unsafe"
        }
        if (!hasCoordinateFallbackIdentity(target)) {
            return "coordinate_fallback_target_unsafe"
        }
        if (!HighRiskClickPolicy.evaluateTexts(
            listOf(target.text, target.contentDescription, target.viewId, target.className)
        ).allowed) {
            return "coordinate_fallback_target_unsafe"
        }
        return null
    }

    fun hasCoordinateFallbackIdentity(target: ClickTargetInfo): Boolean {
        return target.text.isNotBlank() ||
            target.contentDescription.isNotBlank() ||
            target.viewId.isRecognizableCoordinateFallbackViewId()
    }

    private fun String.isRecognizableCoordinateFallbackViewId(): Boolean {
        val value = trim()
        return value.substringBefore(":id/").isNotBlank() &&
            value.substringAfter(":id/", missingDelimiterValue = "").isNotBlank()
    }

    internal fun describeRuleCandidateSignals(node: AccessibilityNodeInfo): RuleCandidateSignals {
        val classNameValue = node.className?.toString().orEmpty()
        val isInput = classNameValue.contains("EditText", ignoreCase = true) || node.isPassword
        return RuleCandidateSignals(
            text = PrivacySanitizer.sanitizeNodeText(node.text?.toString().orEmpty(), isInput),
            contentDescription = PrivacySanitizer.sanitizeNodeText(
                node.contentDescription?.toString().orEmpty(),
                isInput
            ),
            viewId = node.viewIdResourceName.orEmpty(),
            className = classNameValue,
            input = isInput
        )
    }

    fun describeTarget(node: AccessibilityNodeInfo): ClickTargetInfo {
        val parent = AccessibilityNodeAccess.parent(node)
        return describeTarget(
            node = node,
            parentClickable = parent?.isClickable == true,
            signals = describeRuleCandidateSignals(node)
        )
    }

    private fun describeTarget(
        node: AccessibilityNodeInfo,
        parentClickable: Boolean,
        signals: RuleCandidateSignals
    ): ClickTargetInfo {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return ClickTargetInfo(
            bounds = Rect(bounds),
            text = signals.text,
            contentDescription = signals.contentDescription,
            viewId = signals.viewId,
            className = signals.className,
            nodeClickable = node.isClickable,
            parentClickable = parentClickable,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            password = node.isPassword,
            input = signals.input
        )
    }

    internal fun <Node> walkParentChain(
        start: Node,
        maxDepth: Int,
        parentOf: (Node) -> Node?,
        visit: (node: Node, parent: Node?, depth: Int) -> Unit
    ) {
        var current: Node? = start
        var depth = 0
        while (current != null && depth <= maxDepth) {
            val node = current
            val parent = parentOf(node)
            visit(node, parent, depth)
            current = parent
            depth++
        }
    }

    internal fun <Node> collectActionPathValues(
        start: Node,
        parentOf: (Node) -> Node?,
        valuesOf: (Node) -> List<String>
    ): List<String> {
        val values = mutableListOf<String>()
        var current: Node? = start
        repeat(MAX_CLICKABLE_PARENT_DEPTH + 1) {
            val node = current ?: return values
            values += valuesOf(node)
            current = parentOf(node)
        }
        return values
    }

    private fun AccessibilityNodeInfo.isUnsafeActionPathNode(): Boolean {
        val classNameValue = className?.toString().orEmpty()
        val supportsSetText = actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
        return isUnsafeActionPathNodeState(
            editable = isEditable,
            password = isPassword,
            className = classNameValue,
            supportsSetText = supportsSetText,
            enabled = isEnabled,
            visibleToUser = isVisibleToUser
        )
    }

    internal fun isUnsafeActionPathNodeState(
        editable: Boolean,
        password: Boolean,
        className: String,
        supportsSetText: Boolean,
        enabled: Boolean,
        visibleToUser: Boolean
    ): Boolean {
        return editable ||
            password ||
            className.contains("EditText", ignoreCase = true) ||
            supportsSetText ||
            !enabled ||
            !visibleToUser
    }

    fun isTargetPresent(root: AccessibilityNodeInfo?, target: ClickTargetInfo): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.matchesTarget(target)) return true
            for (index in 0 until node.childCount) {
                AccessibilityNodeAccess.child(node, index)?.let(queue::add)
            }
        }
        return false
    }

    fun AccessibilityNodeInfo.isSafeClickTarget(defaultRule: Boolean = false): Boolean {
        if (!isVisibleToUser || !isEnabled || !isClickable || isPassword) return false
        val classNameValue = className?.toString().orEmpty()
        if (classNameValue.contains("EditText", ignoreCase = true)) return false

        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (!bounds.isSafeBounds(defaultRule)) return false

        val text = listOfNotNull(this.text?.toString(), contentDescription?.toString())
            .joinToString(" ")
        if (SafetyGuard.isSensitiveText(text)) return false

        return true
    }

    fun isLargeDefaultCandidate(bounds: Rect): Boolean {
        return bounds.isLargeDefaultBounds()
    }

    fun areaRatio(bounds: Rect): Float {
        return bounds.computeAreaRatio()
    }

    private fun ClickTargetInfo.canUseGestureFallback(allowLargeBounds: Boolean): Boolean {
        if (bounds.isEmpty || input || password || !enabled || !visibleToUser) return false
        val text = listOf(text, contentDescription).joinToString(" ")
        if (SafetyGuard.isSensitiveText(text)) return false
        return bounds.isSafeBounds(defaultRule = !allowLargeBounds)
    }

    private fun Rect.isSafeBounds(defaultRule: Boolean): Boolean {
        if (isEmpty) return false
        val areaRatio = computeAreaRatio()
        if (areaRatio > MAX_CLICK_TARGET_SCREEN_RATIO) return false
        if (defaultRule && isLargeDefaultBounds()) return false
        return width() >= MIN_CLICK_TARGET_SIZE_PX && height() >= MIN_CLICK_TARGET_SIZE_PX
    }

    private fun Rect.isLargeDefaultBounds(): Boolean {
        if (isEmpty) return true
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        return computeAreaRatio() > MAX_DEFAULT_TARGET_SCREEN_RATIO ||
            width().toFloat() / screenWidth > MAX_DEFAULT_TARGET_WIDTH_RATIO ||
            height().toFloat() / screenHeight > MAX_DEFAULT_TARGET_HEIGHT_RATIO
    }

    private fun Rect.computeAreaRatio(): Float {
        if (isEmpty) return 0f
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        return width().toFloat() * height() / (screenWidth * screenHeight)
    }

    private fun AccessibilityNodeInfo.matchesTarget(target: ClickTargetInfo): Boolean {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (!bounds.isNear(target.bounds)) return false

        val viewId = viewIdResourceName.orEmpty()
        if (target.viewId.isNotBlank() || viewId.isNotBlank()) {
            return target.viewId == viewId
        }

        val text = PrivacySanitizer.sanitizeText(text?.toString().orEmpty())
        val description = PrivacySanitizer.sanitizeText(contentDescription?.toString().orEmpty())
        if (target.text.isNotBlank() || text.isNotBlank()) {
            return target.text == text
        }
        if (target.contentDescription.isNotBlank() || description.isNotBlank()) {
            return target.contentDescription == description
        }

        return target.className == className?.toString().orEmpty()
    }

    private fun Rect.isNear(other: Rect): Boolean {
        return abs(left - other.left) <= BOUNDS_TOLERANCE_PX &&
            abs(top - other.top) <= BOUNDS_TOLERANCE_PX &&
            abs(right - other.right) <= BOUNDS_TOLERANCE_PX &&
            abs(bottom - other.bottom) <= BOUNDS_TOLERANCE_PX
    }

    private fun Int.toClickTargetSource(): ClickTargetSourceLog {
        return if (this == 0) {
            ClickTargetSourceLog.NodeSelf
        } else {
            ClickTargetSourceLog.ClickableParent
        }
    }
}

data class ClickAttempt(
    val method: ClickMethodLog,
    val accepted: Boolean,
    val target: ClickTargetInfo,
    val reason: String
)

data class ClickTargetSelection(
    val node: AccessibilityNodeInfo,
    val target: ClickTargetInfo,
    val parentDepth: Int,
    val source: ClickTargetSourceLog
)

internal data class ClickCandidateResolution(
    val candidate: ClickTargetInfo,
    val strictSelection: ClickTargetSelection?,
    val relaxedSelection: ClickTargetSelection?,
    val ancestorSafetyTexts: List<String>,
    val unsafeNodeDepths: Set<Int>
) {
    fun actionPathFor(selection: ClickTargetSelection?): ResolvedActionPath =
        ResolvedActionPath(
            parentDepth = selection?.parentDepth ?: Int.MAX_VALUE,
            hasSafeClickableTarget = selection != null,
            hasUnsafeNode = selection?.let { action ->
                unsafeNodeDepths.any { depth -> depth <= action.parentDepth }
            } == true
        )
}

data class ClickTargetInfo(
    val bounds: Rect,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val nodeClickable: Boolean,
    val parentClickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val password: Boolean,
    val input: Boolean
) {
    fun boundsString(): String {
        return "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    fun summary(): String {
        val label = text.ifBlank { contentDescription }.ifBlank { viewId }.ifBlank { className }
        return boundsString() + if (label.isBlank()) "" else " $label"
    }
}
