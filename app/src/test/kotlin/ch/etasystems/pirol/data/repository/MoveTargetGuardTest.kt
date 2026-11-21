package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.data.repository.MoveTargetGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** T181: Pruefung des Verschiebe-Ziels. */
class MoveTargetGuardTest {
    private val base = listOf("primary:Download/PIROL")
    private val src = listOf("primary:Download/PIROL/2026-11-20/s1")

    @Test fun outsideBase_ok() =
        assertEquals(Verdict.OK, MoveTargetGuard.check("primary:Documents/Archiv", base, src))

    @Test fun otherStorage_ok() =
        assertEquals(Verdict.OK, MoveTargetGuard.check("1234-ABCD:PIROL", base, src))

    @Test fun baseItself_insideBase() =
        assertEquals(Verdict.INSIDE_BASE, MoveTargetGuard.check("primary:Download/PIROL/", base, src))

    @Test fun otherDayFolder_insideBase() =
        assertEquals(Verdict.INSIDE_BASE, MoveTargetGuard.check("primary:Download/PIROL/2026-11-19", base, src))

    @Test fun caseInsensitive() =
        assertEquals(Verdict.INSIDE_BASE, MoveTargetGuard.check("primary:download/pirol/x", base, src))

    @Test fun similarPrefix_notInside() =
        assertEquals(Verdict.OK, MoveTargetGuard.check("primary:Download/PIROL-alt", base, src))

    @Test fun sourceItself_andInside_andParent() {
        assertEquals(Verdict.SAME_AS_SOURCE, MoveTargetGuard.check("primary:Download/PIROL/2026-11-20/s1", base, src))
        assertEquals(Verdict.SAME_AS_SOURCE, MoveTargetGuard.check("primary:Download/PIROL/2026-11-20/s1/memos", base, src))
        assertEquals(Verdict.SAME_AS_SOURCE, MoveTargetGuard.check("primary:Download/PIROL/2026-11-20", base, src))
    }

    @Test fun sourceOutsideBase_parentBlocked() =
        assertEquals(
            Verdict.SAME_AS_SOURCE,
            MoveTargetGuard.check("primary:Doc/A", emptyList(), listOf("primary:Doc/A/s1"))
        )

    @Test fun rootBase_onlyExactBlocked() {
        assertEquals(Verdict.INSIDE_BASE, MoveTargetGuard.check("primary:", listOf("primary:"), emptyList()))
        assertEquals(Verdict.OK, MoveTargetGuard.check("primary:Foo", listOf("primary:"), emptyList()))
    }

    @Test fun noInfo_ok() =
        assertEquals(Verdict.OK, MoveTargetGuard.check("primary:X", emptyList(), emptyList()))
}
