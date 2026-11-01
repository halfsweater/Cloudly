package com.halfblanket.cloudly.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    @Test fun `formats byte sizes`() {
        Locale.setDefault(Locale.US)
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.00 KB", Format.bytes(1024))
        assertEquals("15.0 GB", Format.bytes(15L * 1024 * 1024 * 1024))
        assertEquals("—", Format.bytes(null))
    }

    @Test fun `percent handles unlimited quotas`() {
        assertEquals(0f, Format.percent(10, null))
        assertEquals(0.5f, Format.percent(5, 10))
        assertEquals(1f, Format.percent(50, 10))
    }
}
