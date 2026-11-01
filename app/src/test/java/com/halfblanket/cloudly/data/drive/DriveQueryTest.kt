package com.halfblanket.cloudly.data.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveQueryTest {
    @Test fun `escapes quotes and backslashes in search text`() {
        assertEquals("name contains 'Sam\\'s \\\\ notes' and trashed = false", DriveQuery.nameContains("Sam's \\ notes"))
    }

    @Test fun `children query targets parent and excludes trash`() {
        assertEquals("'abc' in parents and trashed = false", DriveQuery.children("abc"))
        assertTrue(DriveQuery.childFolders("abc").endsWith("mimeType = '${MimeTypes.FOLDER}'"))
    }

    @Test fun `sort order maps to drive orderBy with folders first`() {
        assertEquals("folder,name_natural", SortOrder(SortField.NAME, true).toDriveOrderBy())
        assertEquals("folder,modifiedTime desc", SortOrder(SortField.MODIFIED, false).toDriveOrderBy())
        assertEquals("quotaBytesUsed desc", SortOrder(SortField.SIZE, false).toDriveOrderBy(foldersFirst = false))
    }

    @Test fun `export map covers google workspace types and rejects forms`() {
        assertEquals("docx", MimeTypes.exportFor(MimeTypes.G_DOC)?.extension)
        assertEquals("xlsx", MimeTypes.exportFor(MimeTypes.G_SHEET)?.extension)
        assertEquals("pptx", MimeTypes.exportFor(MimeTypes.G_SLIDES)?.extension)
        assertEquals(null, MimeTypes.exportFor(MimeTypes.G_FORM))
        assertEquals(MimeTypes.G_DOC, MimeTypes.importTargetFor(MimeTypes.G_DOC))
        assertEquals(null, MimeTypes.importTargetFor(MimeTypes.G_DRAWING))
    }
}
