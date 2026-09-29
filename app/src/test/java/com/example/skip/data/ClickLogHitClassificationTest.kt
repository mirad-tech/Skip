package com.example.skip.data

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the success/failure classification used by the statistics screen.
 *
 * The two predicates used to overlap: a log with `stage = ClickEffectUnknown` and `success = true`
 * counted as both a success and a failure, so `success + failure` could exceed `total`.
 */
class ClickLogHitClassificationTest {

    private fun log(stage: ClickLogStage, success: Boolean?): ClickLog {
        return ClickLog(
            timeMillis = 0L,
            packageName = "com.example.app",
            stage = stage,
            success = success
        )
    }

    private val allStages = ClickLogStage.entries

    @Test
    fun successAndFailureAreMutuallyExclusiveForEveryCombination() {
        for (stage in allStages) {
            for (success in listOf(true, false, null)) {
                val candidate = log(stage, success)
                val isSuccess = LogRepository.isSuccessfulHit(candidate)
                val isFailure = LogRepository.isFailureHit(candidate)
                assertFalse(
                    "stage=$stage success=$success must not be both a hit and a failure",
                    isSuccess && isFailure
                )
            }
        }
    }

    @Test
    fun confirmedEffectIsASuccess() {
        assertTrue(LogRepository.isSuccessfulHit(log(ClickLogStage.ClickEffectConfirmed, true)))
        assertTrue(LogRepository.isSuccessfulHit(log(ClickLogStage.ClickEffectConfirmed, null)))
        assertFalse(LogRepository.isFailureHit(log(ClickLogStage.ClickEffectConfirmed, null)))
    }

    @Test
    fun failurePrecedenceBeatsAPositiveSuccessFlag() {
        // Legacy-migrated rows can carry success=true with no stage, which defaults to
        // ClickEffectUnknown. Failure must win so the counters stay within the total.
        val legacyUnknown = log(ClickLogStage.ClickEffectUnknown, true)
        assertFalse(LogRepository.isSuccessfulHit(legacyUnknown))
        assertTrue(LogRepository.isFailureHit(legacyUnknown))

        val legacyFailed = log(ClickLogStage.ClickFailed, true)
        assertFalse(LogRepository.isSuccessfulHit(legacyFailed))
        assertTrue(LogRepository.isFailureHit(legacyFailed))
    }

    @Test
    fun unknownEffectIsAFailureNotASuccess() {
        val unknown = log(ClickLogStage.ClickEffectUnknown, false)
        assertTrue(LogRepository.isFailureHit(unknown))
        assertFalse(LogRepository.isSuccessfulHit(unknown))
    }

    @Test
    fun neitherBucketClaimsAnUnresolvedStage() {
        // A stage that is neither confirmed nor failed and carries no success flag belongs to
        // neither counter, so the totals stay consistent instead of double-counting.
        val unresolved = log(ClickLogStage.ServiceEventReceived, null)
        assertFalse(LogRepository.isSuccessfulHit(unresolved))
        assertFalse(LogRepository.isFailureHit(unresolved))
    }
}
