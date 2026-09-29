package com.example.skip.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the update-version comparison.
 *
 * A malformed remote tag used to be reported as "already up to date" (`isNewer` returned false), so
 * a release-side mistake was invisible in the UI. It now surfaces as an explicit unparseable result
 * that names which side failed.
 */
class VersionComparatorTest {

    @Test
    fun detectsNewerRemoteVersions() {
        assertTrue(VersionComparator.compare("v1.0.18", "1.0.17").isNewerParsed())
        assertTrue(VersionComparator.compare("1.0.17", "1.0.16").isNewerParsed())
        assertTrue(VersionComparator.compare("v2.0.0", "1.99.99").isNewerParsed())
        assertTrue(VersionComparator.compare("1.1", "1.0.9").isNewerParsed())
    }

    @Test
    fun treatsEqualAndOlderRemoteVersionsAsNotNewer() {
        assertFalse(VersionComparator.compare("v1.0.17", "1.0.17").isNewerParsed())
        assertFalse(VersionComparator.compare("v1.0.16", "1.0.17").isNewerParsed())
        // Zero-extension: 1.0 equals 1.0.0.
        assertFalse(VersionComparator.compare("v1.0", "1.0.0").isNewerParsed())
    }

    @Test
    fun normalizesDebugAndBuildSuffixes() {
        assertEquals("1.0.17", VersionComparator.normalizeVersionName("v1.0.17"))
        assertEquals("1.0.17", VersionComparator.normalizeVersionName("1.0.17-debug"))
        assertEquals("1.0.17", VersionComparator.normalizeVersionName("1.0.17+25"))
        assertEquals("1.0.17", VersionComparator.normalizeVersionName("  V1.0.17  "))
        assertFalse(VersionComparator.compare("v1.0.17", "1.0.17-debug").isNewerParsed())
    }

    @Test
    fun unparseableRemoteTagIsNotSilentlyUpToDate() {
        val comparison = VersionComparator.compare("nightly", "1.0.17")
        assertTrue(comparison is VersionComparison.Unparseable)
        val unparseable = comparison as VersionComparison.Unparseable
        assertEquals("nightly", unparseable.value)
        assertEquals(VersionComparator.REMOTE_LABEL, unparseable.source)
    }

    @Test
    fun unparseableLocalVersionNamesTheLocalSide() {
        val comparison = VersionComparator.compare("v1.0.18", "unknown")
        assertTrue(comparison is VersionComparison.Unparseable)
        assertEquals(
            VersionComparator.CURRENT_LABEL,
            (comparison as VersionComparison.Unparseable).source
        )
    }

    private fun VersionComparison.isNewerParsed(): Boolean {
        assertTrue("expected a parsed comparison but was $this", this is VersionComparison.Parsed)
        return (this as VersionComparison.Parsed).isNewer
    }
}
