package com.example.skip.service

import android.view.accessibility.AccessibilityWindowInfo
import com.example.skip.engine.SafetyGuard

/** Classifies the focused window, never a scan-root fallback behind another window. */
internal object ForegroundWindowClassifier {
    fun classify(packageName: String, windowType: Int, className: String): ForegroundObservation {
        val pkg = packageName.trim()
        val lowerPackage = pkg.lowercase(java.util.Locale.ROOT)
        val lowerClass = className.lowercase(java.util.Locale.ROOT)
        if (windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            className == InputMethodWindowPolicy.SOFT_INPUT_WINDOW_CLASS ||
            "permissioncontroller" in lowerPackage || "grantpermissions" in lowerClass
        ) return ForegroundObservation(ForegroundWindowKind.Transient, pkg)
        if (pkg.isBlank()) return ForegroundObservation(ForegroundWindowKind.Unknown)

        // Only explicit navigation evidence rearms the same app. Generic SystemUI does not.
        val knownRecents = lowerPackage.endsWith(".recents") ||
            lowerClass.endsWith(".recentsactivity") || lowerClass.endsWith(".recents.recentsactivity")
        if (knownRecents ||
            (windowType == AccessibilityWindowInfo.TYPE_APPLICATION && SafetyGuard.isLauncherPackage(pkg))
        ) return ForegroundObservation(ForegroundWindowKind.Navigation, pkg)
        if (windowType != AccessibilityWindowInfo.TYPE_APPLICATION || "systemui" in lowerPackage) {
            return ForegroundObservation(ForegroundWindowKind.Transient, pkg)
        }
        return ForegroundObservation(ForegroundWindowKind.Application, pkg)
    }
}
