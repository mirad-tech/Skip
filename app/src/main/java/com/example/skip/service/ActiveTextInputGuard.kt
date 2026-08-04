package com.example.skip.service

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.util.AccessibilityNodeAccess

object ActiveTextInputGuard {
    fun hasFocusedEditableInput(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isActiveTextInput()) return true
            for (index in 0 until node.childCount) {
                AccessibilityNodeAccess.child(node, index)?.let(queue::add)
            }
        }
        return false
    }

    private fun AccessibilityNodeInfo.isActiveTextInput(): Boolean {
        if (!isVisibleToUser || !isEnabled || !isFocused) return false
        return isEditable || isEditTextClass() || supportsSetTextAction()
    }

    private fun AccessibilityNodeInfo.isEditTextClass(): Boolean {
        return className?.toString().orEmpty().contains("EditText", ignoreCase = true)
    }

    private fun AccessibilityNodeInfo.supportsSetTextAction(): Boolean {
        return actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }
    }
}
