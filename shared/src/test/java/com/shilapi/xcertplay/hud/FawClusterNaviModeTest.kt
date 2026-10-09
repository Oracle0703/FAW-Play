package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FawClusterNaviModeTest {
    @Test
    fun readsTheModeFromServiceCallOutput() {
        assertEquals(FawClusterNaviMode.TURN_ON_BY_NAVI, FawClusterNaviMode.parseRead("Result: Parcel(00000000 00000002   '........')"))
        assertEquals(FawClusterNaviMode.FULL, FawClusterNaviMode.parseRead("Result: Parcel(00000000 00000004   '........')"))
        // An exception code in the first word, an unknown value, or no parcel at all.
        assertNull(FawClusterNaviMode.parseRead("Result: Parcel(ffffffff 00000002   '........')"))
        assertNull(FawClusterNaviMode.parseRead("Result: Parcel(00000000 00000009   '........')"))
        assertNull(FawClusterNaviMode.parseRead(null))
    }

    @Test
    fun onlySmallAndFullShowTheMap() {
        assertEquals(
            listOf(FawClusterNaviMode.SMALL, FawClusterNaviMode.FULL),
            FawClusterNaviMode.entries.filter { it.showsMap },
        )
    }

    @Test
    fun readsTheVerifiedId() {
        assertEquals("service call autoservice 5 i32 1007 i32 1086337074", FawClusterNaviMode.READ_COMMAND)
    }
}
