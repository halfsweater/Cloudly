package com.halfblanket.cloudly.ui.storage

import com.halfblanket.cloudly.data.drive.FileCategory
import com.halfblanket.cloudly.data.drive.testFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class StorageScanTest {
    private val mb = 1024L * 1024

    @Test fun `finds duplicate groups by checksum and size, oldest first`() {
        val scan = StorageScan(
            "acc1",
            listOf(
                testFile("b", md5 = "x", size = 10 * mb, created = Instant.parse("2022-01-01T00:00:00Z")),
                testFile("a", md5 = "x", size = 10 * mb, created = Instant.parse("2021-01-01T00:00:00Z")),
                testFile("c", md5 = "y", size = 5 * mb),
                testFile("d", md5 = "x", size = 9 * mb), // same checksum, different size: not a duplicate
            ),
            complete = true,
        )
        val groups = scan.duplicates()
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.first().map { it.id })
        assertEquals(10 * mb, scan.reclaimable(CleanupKind.DUPLICATES))
    }

    @Test fun `large videos and old files`() {
        val old = Instant.now().minus(400, ChronoUnit.DAYS)
        val scan = StorageScan(
            "acc1",
            listOf(
                testFile("v1", mime = "video/mp4", size = 200 * mb, created = Instant.now()),
                testFile("v2", mime = "video/mp4", size = 50 * mb, created = Instant.now()),
                testFile("o1", size = 30 * mb, created = old, modified = old, viewed = null),
                testFile("o2", size = 30 * mb, created = old, modified = old, viewed = Instant.now()),
            ),
            complete = true,
        )
        assertEquals(listOf("v1"), scan.filesFor(CleanupKind.VIDEOS).map { it.id })
        assertEquals(listOf("o1"), scan.filesFor(CleanupKind.OLD).map { it.id })
        assertEquals(FileCategory.VIDEO, scan.byCategory.first().first)
    }
}
