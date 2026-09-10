package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scroll-lock mode: which axis it drops, and the order the top-bar button cycles in. */
class ScrollLockTest {

    @Test fun `unlocked passes both axes through`() {
        assertEquals(7f, ScrollLock.NONE.allowX(7f), 0f)
        assertEquals(7f, ScrollLock.NONE.allowY(7f), 0f)
    }

    @Test fun `each mode drops its own axis only`() {
        assertEquals(0f, ScrollLock.HORIZONTAL.allowX(7f), 0f)
        assertEquals(7f, ScrollLock.HORIZONTAL.allowY(7f), 0f)
        assertEquals(7f, ScrollLock.VERTICAL.allowX(7f), 0f)
        assertEquals(0f, ScrollLock.VERTICAL.allowY(7f), 0f)
    }

    @Test fun `tapping the button walks every mode and comes back to unlocked`() {
        assertEquals(ScrollLock.HORIZONTAL, ScrollLock.NONE.next())
        assertEquals(ScrollLock.VERTICAL, ScrollLock.HORIZONTAL.next())
        assertEquals(ScrollLock.NONE, ScrollLock.VERTICAL.next())
    }

    @Test fun `every mode says what it does`() {
        ScrollLock.entries.forEach {
            assertEquals(false, it.label.isBlank())
            assertEquals(false, it.description.isBlank())
        }
    }
}
