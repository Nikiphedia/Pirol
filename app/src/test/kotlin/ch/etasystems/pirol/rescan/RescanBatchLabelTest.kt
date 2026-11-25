package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.ui.analysis.RescanBatchLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T309: "Session i von n" nur bei mehreren Sessions */
class RescanBatchLabelTest {
    @Test fun singleSessionShowsNothing() = assertNull(RescanBatchLabel.position(0, 1))
    @Test fun firstOfThree() = assertEquals(1, RescanBatchLabel.position(0, 3))
    @Test fun lastOfThree() = assertEquals(3, RescanBatchLabel.position(2, 3))
    @Test fun outOfRangeIsClamped() = assertEquals(3, RescanBatchLabel.position(7, 3))
}
