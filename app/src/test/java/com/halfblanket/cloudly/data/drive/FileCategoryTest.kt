package com.halfblanket.cloudly.data.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {
    @Test fun `classifies common types`() {
        assertEquals(FileCategory.FOLDER, FileCategory.of(MimeTypes.FOLDER))
        assertEquals(FileCategory.IMAGE, FileCategory.of("image/jpeg", "a.jpg"))
        assertEquals(FileCategory.VIDEO, FileCategory.of("video/mp4", "a.mp4"))
        assertEquals(FileCategory.AUDIO, FileCategory.of("audio/mpeg", "a.mp3"))
        assertEquals(FileCategory.PDF, FileCategory.of("application/pdf", "a.pdf"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of(MimeTypes.G_DOC))
        assertEquals(FileCategory.SPREADSHEET, FileCategory.of(MimeTypes.XLSX, "a.xlsx"))
        assertEquals(FileCategory.PRESENTATION, FileCategory.of(MimeTypes.G_SLIDES))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of("application/zip", "a.zip"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.of("application/octet-stream", "backup.7z"))
        assertEquals(FileCategory.SPREADSHEET, FileCategory.of("text/csv", "data.csv"))
        assertEquals(FileCategory.OTHER, FileCategory.of("application/octet-stream", "blob.bin"))
    }
}
