package com.example.skip.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the policy merge rules that two real defects violated:
 *  - `saveBlacklistPackages` rebuilt blacklisted policies from scratch, resetting
 *    `customRulesEnabled` to true and silently re-enabling custom rules (including their coordinate
 *    fallback) on every other blacklisted app;
 *  - a JSON rule import replaced the whole policy, doing the same thing to the imported app.
 */
class AppPolicyMergeTest {

    @Test
    fun asBlacklistedPreservesUnrelatedChoices() {
        val existing = AppPolicy(
            packageName = "com.example.app",
            defaultRuleEnabled = true,
            customRulesEnabled = false,
            migratedFromBlacklist = false,
            updatedAt = 1_000L
        )

        val blacklisted = existing.asBlacklisted()

        assertFalse(blacklisted.defaultRuleEnabled)
        assertTrue(blacklisted.migratedFromBlacklist)
        assertFalse("customRulesEnabled must survive blacklisting", blacklisted.customRulesEnabled)
        assertEquals("updatedAt must not be re-stamped", 1_000L, blacklisted.updatedAt)
        assertEquals("com.example.app", blacklisted.packageName)
    }

    @Test
    fun asBlacklistedIsIdempotent() {
        val once = AppPolicy(packageName = "com.example.app", customRulesEnabled = false)
            .asBlacklisted()
        val twice = once.asBlacklisted()

        assertEquals(once, twice)
    }

    @Test
    fun legacyBlacklistFactoryIsTheFirstTimePath() {
        // Kept for packages with no stored policy at all; here there is nothing to preserve.
        val created = AppPolicy.fromLegacyBlacklist("com.example.new")
        assertFalse(created.defaultRuleEnabled)
        assertTrue(created.customRulesEnabled)
        assertTrue(created.migratedFromBlacklist)
    }

    @Test
    fun effectivePolicyBlocksSelfAndProtectedPackages() {
        val permissive = AppPolicy(packageName = "com.example.app")
        val selfPolicy = AppPolicy.effectiveFor(
            policy = permissive,
            packageName = "com.example.skip",
            selfPackageName = "com.example.skip"
        )
        assertTrue(selfPolicy.blockedBySafety)
        assertFalse(selfPolicy.defaultRuleEnabled)
        assertFalse(selfPolicy.customRulesEnabled)

        val protectedPolicy = AppPolicy.effectiveFor(
            policy = permissive,
            packageName = "com.tencent.mm",
            selfPackageName = "com.example.skip"
        )
        assertTrue(protectedPolicy.blockedBySafety)
        assertFalse(protectedPolicy.defaultRuleEnabled)
    }

    @Test
    fun effectivePolicyHonoursUserSwitches() {
        val disabled = AppPolicy(
            packageName = "com.example.app",
            defaultRuleEnabled = false,
            customRulesEnabled = false
        )
        val effective = AppPolicy.effectiveFor(
            policy = disabled,
            packageName = "com.example.app",
            selfPackageName = "com.example.skip"
        )
        assertFalse(effective.blockedBySafety)
        assertFalse(effective.defaultRuleEnabled)
        assertFalse(effective.customRulesEnabled)
    }
}
