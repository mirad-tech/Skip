package com.example.skip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for the protected-package matcher.
 *
 * `SafetyGuard` decides which apps are never auto-clicked. Two real defects reached this file during
 * the audit and are pinned here:
 *  - the `citic` keyword was deleted by accident, silently un-protecting the `com.citics.*` brokerage
 *    apps, so the whole keyword list is asserted rather than a sample;
 *  - short tokens like `ime`/`pay` matched anywhere in the package name, so `com.example.timekeeper`
 *    was treated as an input method and never skipped.
 */
class SafetyGuardMatchingTest {

    private val mustBeProtected = listOf(
        // Brokerage / banking: "citic" is the keyword that covers the com.citics.* namespace,
        // because protectedPrefixes only lists com.citicbank.
        "com.citics.csb.inte",
        "com.citics.cagent",
        "com.citics.csb",
        "com.citics.ecard",
        "com.ecitic.bank.mobile",
        "com.citicbank.comb",
        "com.icbc",
        "com.icbc.im",
        "cn.com.icbc.mobile",
        "com.cmbchina.ccd.pluto.cmbActivity",
        "cmb.pb",
        "com.ccb.life",
        "cn.com.boc.mobilebank",
        // Covered by the exact-package list only (no keyword matches the name).
        "com.ccb",
        "com.bankcomm",
        "com.eg.android.AlipayGphone",
        "com.tencent.mm",
        "com.unionpay",
        // Financial tokens embedded in a word keep their full-name match.
        "com.jd.lib.jdpaysdk",
        "com.paybyphone",
        "com.mycashier.pos",
        "com.tenpay.android",
        // Payment tokens must still match on a segment boundary even though they are boundary-matched.
        "com.tenpay.android",
        "com.google.android.apps.walletnfcrel",
        // Input methods: "inputmethod" keeps the broad full-name match, "ime" keeps boundary matching.
        "com.sohu.inputmethod.sogou",
        "com.google.android.inputmethod.latin",
        "com.lge.ime",
        "com.sogou.ime",
        "com.android.inputmethod.latin",
        // System surfaces.
        "com.android.systemui",
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.coloros.recents",
        "com.miui.home",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        // Password managers / permission controller.
        "com.bitwarden.mobile",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    private val mustNotBeProtected = listOf(
        // The headline false positive: "time" is not the segment "ime".
        "com.example.timekeeper",
        "com.time.mobile",
        "com.prime.video",
        "com.example.notes",
        "com.tencent.tmgp.sgame",
        "com.zhihu.android",
        "com.ss.android.ugc.aweme"
    )

    @Test
    fun protectsKnownSensitivePackages() {
        mustBeProtected.forEach { packageName ->
            assertTrue(
                "$packageName must be protected",
                SafetyGuard.isProtectedPackage(packageName)
            )
        }
    }

    @Test
    fun doesNotProtectUnrelatedPackages() {
        mustNotBeProtected.forEach { packageName ->
            assertFalse(
                "$packageName must not be protected",
                SafetyGuard.isProtectedPackage(packageName)
            )
        }
    }

    @Test
    fun matchedKeywordContractIsKeywordOnly() {
        // isSystemPackage and protectedPackageReason compare this value against keyword literals, so
        // it must never leak a package name or a prefix (that mismatch sent every exact/prefix hit
        // down the generic branch).
        assertEquals("citic", SafetyGuard.matchedProtectedKeyword("com.citics.csb.inte"))
        assertEquals("settings", SafetyGuard.matchedProtectedKeyword("com.android.settings"))
        assertEquals("ime", SafetyGuard.matchedProtectedKeyword("com.lge.ime"))
        assertEquals("inputmethod", SafetyGuard.matchedProtectedKeyword("com.sohu.inputmethod.sogou"))
        // Exact-list and prefix hits report the keyword when one also matches; com.tencent.mm is only
        // in the exact list, so no keyword applies.
        assertNull(SafetyGuard.matchedProtectedKeyword("com.tencent.mm"))
    }

    @Test
    fun classifiesSystemPackagesOnKeywords() {
        listOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.coloros.recents"
        ).forEach { packageName ->
            assertTrue("$packageName must be a system package", SafetyGuard.isSystemPackage(packageName))
        }
        listOf(
            "com.miui.home",
            "com.bbk.launcher2",
            "com.lge.ime",
            "com.tencent.mm",
            "com.example.notes",
            "com.example.timekeeper",
            "com.google.android.apps.walletnfcrel"
        ).forEach { packageName ->
            assertFalse("$packageName must not be a system package", SafetyGuard.isSystemPackage(packageName))
        }
    }

    @Test
    fun reportsSpecificReasonStrings() {
        assertEquals(
            "settings_or_permission_package",
            SafetyGuard.protectedPackageReason("com.android.settings")
        )
        assertEquals(
            "package_installer_package",
            SafetyGuard.protectedPackageReason("com.android.packageinstaller")
        )
        assertEquals(
            "input_method_package",
            SafetyGuard.protectedPackageReason("com.sohu.inputmethod.sogou")
        )
        assertEquals(
            "system_or_launcher_package",
            SafetyGuard.protectedPackageReason("com.android.systemui")
        )
        assertEquals(
            "protected_sensitive_package",
            SafetyGuard.protectedPackageReason("com.citics.csb.inte")
        )
        assertEquals("", SafetyGuard.protectedPackageReason("com.example.notes"))
    }

    @Test
    fun onlyTheImeTokenRequiresAWholeSegment() {
        // "ime" is the one token narrowed to a whole segment, because it is a substring of ordinary
        // words: matching it across the name disabled skipping for these apps.
        assertTrue(SafetyGuard.isProtectedPackage("com.lge.ime"))
        assertTrue(SafetyGuard.isProtectedPackage("com.sogou.ime"))
        assertTrue(SafetyGuard.isProtectedPackage("com.baidu.ime.android"))
        assertFalse(SafetyGuard.isProtectedPackage("com.time.mobile"))
        assertFalse(SafetyGuard.isProtectedPackage("com.example.timekeeper"))
        assertFalse(SafetyGuard.isProtectedPackage("com.prime.video"))

        // The financial tokens keep the broader match, because they carry real signal inside a word
        // and no common package word contains them by accident.
        assertTrue(SafetyGuard.isProtectedPackage("com.tenpay.android"))
        assertTrue(SafetyGuard.isProtectedPackage("com.jd.lib.jdpaysdk"))
        assertTrue(SafetyGuard.isProtectedPackage("com.paybyphone"))
        assertTrue(SafetyGuard.isProtectedPackage("com.mycashier.pos"))
        assertTrue(SafetyGuard.isProtectedPackage("com.cmbchina.ccd.pluto.cmbActivity"))
        assertTrue(SafetyGuard.isProtectedPackage("com.citics.csb.inte"))

        // "settings" keeps its full-name match (unchanged from the previous release), so a package
        // that merely contains the word stays classified with the system surface.
        assertTrue(SafetyGuard.isSystemPackage("com.example.settingshelper"))
        assertTrue(SafetyGuard.isSystemPackage("com.android.settings"))
    }
}
