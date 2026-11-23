package ch.etasystems.pirol.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartFixPolicyTest {
    private val now = 10_000_000L
    private fun fix(ageMs: Long, acc: Float?) = LocationData(47.0, 8.0, null, acc, now - ageMs)

    @Test fun noFixNeedsFix() = assertTrue(StartFixPolicy.needsFix(null, now, 50f))
    @Test fun youngAccurateFixIsEnough() = assertFalse(StartFixPolicy.needsFix(fix(60_000, 10f), now, 50f))
    @Test fun oldFixNeedsFix() = assertTrue(StartFixPolicy.needsFix(fix(121_000, 10f), now, 50f))
    @Test fun inaccurateFixNeedsFix() = assertTrue(StartFixPolicy.needsFix(fix(1_000, 80f), now, 50f))
    @Test fun unknownAccuracyNeedsFix() = assertTrue(StartFixPolicy.needsFix(fix(1_000, null), now, 50f))

    @Test fun publishWhenNoCurrent() = assertTrue(StartFixPolicy.shouldPublish(null, fix(0, 20f), 50f))
    @Test fun publishNewerFix() = assertTrue(StartFixPolicy.shouldPublish(fix(300_000, 10f), fix(0, 20f), 50f))
    @Test fun noPublishWhenInaccurate() = assertFalse(StartFixPolicy.shouldPublish(null, fix(0, 90f), 50f))
    @Test fun noPublishWhenOlder() = assertFalse(StartFixPolicy.shouldPublish(fix(0, 10f), fix(5_000, 10f), 50f))
    @Test fun noPublishWithoutAccuracy() = assertFalse(StartFixPolicy.shouldPublish(null, fix(0, null), 50f))
}
