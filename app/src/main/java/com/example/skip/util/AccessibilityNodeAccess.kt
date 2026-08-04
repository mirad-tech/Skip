package com.example.skip.util

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

// The framework accepts zero as an empty prefetch bitmask, but its @IntDef omits zero.
@SuppressLint("WrongConstant")
internal object AccessibilityNodeAccess {
    fun freshActiveRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return service.rootInActiveWindow
        }

        clearCache(service)
        return service.getRootInActiveWindow(NO_PREFETCH)
    }

    fun clearCache(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return service.clearCache()
    }

    inline fun <T> withCacheBoundary(
        service: AccessibilityService,
        block: () -> T
    ): T {
        clearCache(service)
        return try {
            block()
        } finally {
            clearCache(service)
        }
    }

    fun root(window: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            window.getRoot(NO_PREFETCH)
        } else {
            window.root
        }
    }

    fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.getChild(index, NO_PREFETCH)
        } else {
            node.getChild(index)
        }
    }

    fun parent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.getParent(NO_PREFETCH)
        } else {
            node.parent
        }
    }

    private const val NO_PREFETCH = 0
}
