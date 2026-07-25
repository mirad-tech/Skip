package com.example.skip.service

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.skip.engine.SafetyGuard

internal class AccessibilityWindowResolver(
    private val selfPackageName: String,
    private val activeRootProvider: () -> AccessibilityNodeInfo?,
    private val interactiveWindowsProvider: () -> List<AccessibilityWindowInfo>
) {
    fun selectRootForEvent(eventPackageName: String): RootSelection {
        return selectRoot(
            preferredPackageName = eventPackageName,
            allowSingleExternalFallback = true
        )
    }

    fun selectRootForPackage(packageName: String): RootSelection {
        return selectRoot(
            preferredPackageName = packageName,
            allowSingleExternalFallback = false
        )
    }

    private fun selectRoot(
        preferredPackageName: String,
        allowSingleExternalFallback: Boolean
    ): RootSelection {
        val activeRoot = activeRootProvider()
        val activePackage = activeRoot?.packageName?.toString().orEmpty().trim()
        val preferredPackage = preferredPackageName.trim()
        if (activeRoot != null &&
            activePackage.isUsableScanPackage() &&
            (preferredPackage.isBlank() ||
                activePackage == preferredPackage ||
                !preferredPackage.isUsableScanPackage())
        ) {
            return RootSelection(activeRoot)
        }

        if (preferredPackage.isUsableScanPackage()) {
            interactiveWindowRoots()
                .firstOrNull { it.packageName == preferredPackage }
                ?.let { root ->
                    return RootSelection(
                        root = root.root,
                        detail = "interactive_window_root_selected:package=${root.packageName}"
                    )
                }
        }

        if (allowSingleExternalFallback &&
            activeRoot != null &&
            !activePackage.isUsableScanPackage()
        ) {
            val usableRoots = interactiveWindowRoots()
                .filter { it.packageName.isUsableScanPackage() }
                .distinctBy { it.packageName }
            if (usableRoots.size == 1) {
                val root = usableRoots.single()
                return RootSelection(
                    root = root.root,
                    detail = "single_interactive_window_root_selected:package=${root.packageName}"
                )
            }
        }

        return RootSelection(activeRoot)
    }

    private fun interactiveWindowRoots(): List<WindowRoot> {
        return runCatching { interactiveWindowsProvider() }
            .getOrNull()
            .orEmpty()
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val packageName = root.packageName?.toString().orEmpty().trim()
                if (packageName.isBlank()) null else WindowRoot(root, packageName)
            }
    }

    private fun String.isUsableScanPackage(): Boolean {
        return isNotBlank() &&
            this != selfPackageName &&
            !SafetyGuard.isProtectedPackage(this)
    }
}

internal data class RootSelection(
    val root: AccessibilityNodeInfo?,
    val detail: String = ""
)

private data class WindowRoot(
    val root: AccessibilityNodeInfo,
    val packageName: String
)
