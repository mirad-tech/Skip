package com.example.skip

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityCacheBoundarySourceTest {
    @Test
    fun android13NodeReadsDisableFrameworkPrefetch() {
        val access = readProjectFile(
            "app/src/main/java/com/example/skip/util/AccessibilityNodeAccess.kt"
        )
        val clickExecutor = readProjectFile(
            "app/src/main/java/com/example/skip/engine/ClickExecutor.kt"
        )

        assertTrue(access.contains("private const val NO_PREFETCH = 0"))
        assertTrue(access.contains("getRootInActiveWindow(NO_PREFETCH)"))
        assertTrue(access.contains("window.getRoot(NO_PREFETCH)"))
        assertTrue(access.contains("node.getChild(index, NO_PREFETCH)"))
        assertTrue(access.contains("node.getParent(NO_PREFETCH)"))
        assertFalse(access.contains("FLAG_PREFETCH_DESCENDANTS"))
        assertTrue(clickExecutor.contains("parentOf = AccessibilityNodeAccess::parent"))
        assertFalse(clickExecutor.contains("AccessibilityNodeInfo::getParent"))
    }

    @Test
    fun serviceAndDelayedWorkUseCacheBoundaries() {
        val service = readProjectFile(
            "app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt"
        )
        val pending = readProjectFile(
            "app/src/main/java/com/example/skip/service/PendingClickCoordinator.kt"
        )

        assertTrue(service.contains("AccessibilityNodeAccess.withCacheBoundary(this)"))
        assertTrue(service.contains("AccessibilityNodeAccess.withCacheBoundary(this, action)"))
        assertTrue(pending.contains("AccessibilityNodeAccess.withCacheBoundary(service, action)"))
        assertFalse(pending.contains("service.rootInActiveWindow"))
    }

    @Test
    fun android13ServiceDisablesFrameworkAccessibilityCache() {
        val service = readProjectFile(
            "app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt"
        )
        val onServiceConnected = service
            .substringAfter("override fun onServiceConnected()")
            .substringBefore("override fun onAccessibilityEvent")

        assertTrue(onServiceConnected.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(onServiceConnected.contains("setCacheEnabled(false)"))
    }

    @Test
    fun debugOnlyLogIsSkippedBeforePayloadConstruction() {
        val logger = readProjectFile(
            "app/src/main/java/com/example/skip/service/ServiceEventLogger.kt"
        )
        val earlyReturn = logger.indexOf(
            "if (stage.isDebugOnly && !SettingsRepository.isDebugToastEnabled(service)) return"
        )
        val payloadBuild = logger.indexOf("ClickLogEventFactory.build(")

        assertTrue(earlyReturn >= 0)
        assertTrue(payloadBuild > earlyReturn)
    }

    private fun readProjectFile(path: String): String {
        return listOf(File(path), File("../$path")).first { it.exists() }.readText()
    }
}
