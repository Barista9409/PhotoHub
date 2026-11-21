package com.photohub.app

import com.photohub.app.data.Settings
import com.photohub.app.data.Window
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which photos stay in the Google Drive window. */
class WindowTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L
    private val gb = 1024L * 1024 * 1024
    // newest first: a (1 day, 4GB), b (40 days, 4GB), c (100 days, 4GB)
    private val items = listOf("c" to ((now - 100 * day) to 4 * gb), "a" to ((now - day) to 4 * gb), "b" to ((now - 40 * day) to 4 * gb))

    @Test fun byMonths() {
        assertEquals(setOf("a", "b"), Window.keep(items, Settings(driveMode = "months", driveMonths = 3), now))
        assertEquals(setOf("a"), Window.keep(items, Settings(driveMode = "months", driveMonths = 1), now))
    }

    @Test fun byGb() {
        assertEquals(setOf("a", "b"), Window.keep(items, Settings(driveMode = "gb", driveGb = 10), now))
        assertEquals(setOf("a", "b", "c"), Window.keep(items, Settings(driveMode = "gb", driveGb = 12), now))
    }

    @Test fun bothWhicheverFirst() {
        assertEquals(setOf("a"), Window.keep(items, Settings(driveMode = "both", driveMonths = 6, driveGb = 5), now))
        assertEquals(setOf("a"), Window.keep(items, Settings(driveMode = "both", driveMonths = 1, driveGb = 100), now))
    }
}
