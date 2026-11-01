package com.halfblanket.cloudly.data.drive

enum class FileCategory(val label: String) {
    FOLDER("Folders"),
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    PDF("PDFs"),
    DOCUMENT("Documents"),
    SPREADSHEET("Spreadsheets"),
    PRESENTATION("Presentations"),
    ARCHIVE("Archives"),
    OTHER("Other");

    companion object {
        private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        private val docExt = setOf("doc", "docx", "odt", "rtf", "txt", "md", "pages")
        private val sheetExt = setOf("xls", "xlsx", "ods", "csv", "numbers", "tsv")
        private val slideExt = setOf("ppt", "pptx", "odp", "key")

        fun of(mime: String, name: String = ""): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime == MimeTypes.FOLDER -> FOLDER
                mime.startsWith("image/") || mime == MimeTypes.G_DRAWING -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime == MimeTypes.PDF -> PDF
                mime == MimeTypes.G_SHEET || mime.contains("spreadsheet") || mime.contains("ms-excel") ||
                    ext in sheetExt -> SPREADSHEET
                mime == MimeTypes.G_SLIDES || mime.contains("presentation") || mime.contains("powerpoint") ||
                    ext in slideExt -> PRESENTATION
                mime == MimeTypes.G_DOC || mime.contains("wordprocessing") || mime == "application/msword" ||
                    mime.startsWith("text/") || ext in docExt -> DOCUMENT
                mime.contains("zip") || mime.contains("compressed") || mime.contains("x-tar") ||
                    mime.contains("x-7z") || mime.contains("x-rar") || ext in archiveExt -> ARCHIVE
                else -> OTHER
            }
        }
    }
}
