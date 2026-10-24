package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutunnClusterNaviModeTest {
    @Test
    fun readsTheModeFromServiceCallOutput() {
        assertEquals(AutunnClusterNaviMode.TURN_ON_BY_NAVI, AutunnClusterNaviMode.parseRead("Result: Parcel(00000000 00000002   '........')"))
        assertEquals(AutunnClusterNaviMode.FULL, AutunnClusterNaviMode.parseRead("Result: Parcel(00000000 00000004   '........')"))
        // An exception code in the first word, an unknown value, or no parcel at all.
        assertNull(AutunnClusterNaviMode.parseRead("Result: Parcel(ffffffff 00000002   '........')"))
        assertNull(AutunnClusterNaviMode.parseRead("Result: Parcel(00000000 00000009   '........')"))
        assertNull(AutunnClusterNaviMode.parseRead(null))
    }

    @Test
    fun onlySmallAndFullShowTheMap() {
        assertEquals(
            listOf(AutunnClusterNaviMode.SMALL, AutunnClusterNaviMode.FULL),
            AutunnClusterNaviMode.entries.filter { it.showsMap },
        )
    }

    @Test
    fun readsTheVerifiedId() {
        assertEquals("service call autoservice 5 i32 1007 i32 1086337074", AutunnClusterNaviMode.READ_COMMAND)
    }
}
