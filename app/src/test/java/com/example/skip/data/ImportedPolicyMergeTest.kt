package com.example.skip.data

import com.example.skip.model.AppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule-import policy merge.
 *
 * A JSON rule package that lists an `appPolicies` entry used to replace the whole stored policy.
 * The imported entry defaults `customRulesEnabled` to true, so importing a package silently
 * re-enabled that app's custom rules — the same class of defect as rebuilding a blacklisted policy
 * through `AppPolicy.fromLegacyBlacklist`.
 */
class ImportedPolicyMergeTest {

    @Test
    fun importMayChangeTheDefaultRuleSwitch() {
        val existing = AppPolicy(packageName = "com.example.app", defaultRuleEnabled = true)
        val imported = AppPolicy(packageName = "com.example.app", defaultRuleEnabled = false)

        val merged = SettingsRepository.mergeImportedPolicy(existing, imported)

        assertFalse(merged.defaultRuleEnabled)
    }

    @Test
    fun importCannotReEnableCustomRules() {
        val existing = AppPolicy(
            packageName = "com.example.app",
            customRulesEnabled = false,
            updatedAt = 1_000L
        )
        // A rule package that omits customRulesEnabled parses to the default, true.
        val imported = AppPolicy(packageName = "com.example.app", customRulesEnabled = true)

        val merged = SettingsRepository.mergeImportedPolicy(existing, imported)

        assertFalse("import must not re-enable custom rules", merged.customRulesEnabled)
        assertEquals("updatedAt must not be re-stamped by an import", 1_000L, merged.updatedAt)
    }

    @Test
    fun importCannotForgeABlacklistMigration() {
        val existing = AppPolicy(
            packageName = "com.example.app",
            defaultRuleEnabled = false,
            migratedFromBlacklist = false
        )
        val imported = AppPolicy(
            packageName = "com.example.app",
            defaultRuleEnabled = false,
            migratedFromBlacklist = true
        )

        val merged = SettingsRepository.mergeImportedPolicy(existing, imported)

        assertFalse(
            "an import must not make the app look like a user blacklist entry",
            merged.migratedFromBlacklist
        )
    }

    @Test
    fun importKeepsAManualBlacklistEntryBlacklisted() {
        val existing = AppPolicy(
            packageName = "com.example.app",
            defaultRuleEnabled = false,
            migratedFromBlacklist = true
        )
        val imported = AppPolicy(packageName = "com.example.app", defaultRuleEnabled = true)

        val merged = SettingsRepository.mergeImportedPolicy(existing, imported)

        // The default-rule switch follows the import, but the migration flag stays so
        // clearBlacklist keeps treating the entry as the user's own blacklist decision.
        assertTrue(merged.defaultRuleEnabled)
        assertTrue(merged.migratedFromBlacklist)
    }

    @Test
    fun importPreservesThePackageName() {
        val existing = AppPolicy(packageName = "com.example.app", customRulesEnabled = false)
        val imported = AppPolicy(packageName = "com.example.app")

        val merged = SettingsRepository.mergeImportedPolicy(existing, imported)

        assertTrue(merged.packageName == "com.example.app")
    }
}
