package com.example.skip.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.util.PrivacySanitizer
import kotlin.math.abs

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

    internal fun resolveCandidate(node: AccessibilityNodeInfo): ClickCandidateResolution {
        return ClickCandidateResolution(
            candidate = describeTarget(node),
            strictSelection = findClickableSelection(node, defaultRule = true),
            relaxedSelection = findClickableSelection(node, defaultRule = false),
            ancestorSafetyTexts = node.collectAncestorSafetyTexts()
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
            current = current.parent
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
    ) {
        if (!target.canUseGestureFallback(allowLargeBounds)) {
            onResult(
                ClickAttempt(
                    method = ClickMethodLog.DispatchGesture,
                    accepted = false,
                    target = target,
                    reason = "gesture_fallback_not_safe"
                )
            )
            return
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
    }

    fun gestureClickPoint(
        service: AccessibilityService,
        target: ClickTargetInfo,
        x: Int,
        y: Int,
        onResult: (ClickAttempt) -> Unit
    ) {
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
            return
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
            return
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

    fun describeTarget(node: AccessibilityNodeInfo): ClickTargetInfo {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val parent = node.parent
        val classNameValue = node.className?.toString().orEmpty()
        val isInput = classNameValue.contains("EditText", ignoreCase = true) || node.isPassword
        return ClickTargetInfo(
            bounds = Rect(bounds),
            text = PrivacySanitizer.sanitizeNodeText(node.text?.toString().orEmpty(), isInput),
            contentDescription = PrivacySanitizer.sanitizeNodeText(
                node.contentDescription?.toString().orEmpty(),
                isInput
            ),
            viewId = node.viewIdResourceName.orEmpty(),
            className = classNameValue,
            nodeClickable = node.isClickable,
            parentClickable = parent?.isClickable == true,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            password = node.isPassword,
            input = isInput
        )
    }

    private fun AccessibilityNodeInfo.collectAncestorSafetyTexts(): List<String> {
        val values = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = this
        repeat(4) {
            val ancestor = current ?: return values
            values += listOf(
                ancestor.text?.toString().orEmpty(),
                ancestor.contentDescription?.toString().orEmpty(),
                ancestor.viewIdResourceName.orEmpty(),
                ancestor.className?.toString().orEmpty()
            )
            current = ancestor.parent
        }
        return values
    }

    fun isTargetPresent(root: AccessibilityNodeInfo?, target: ClickTargetInfo): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.matchesTarget(target)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
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
    val ancestorSafetyTexts: List<String>
) {
    fun actionPathFor(selection: ClickTargetSelection?): ResolvedActionPath =
        ResolvedActionPath(
            parentDepth = selection?.parentDepth ?: Int.MAX_VALUE,
            hasSafeClickableTarget = selection != null
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
