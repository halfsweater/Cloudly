package com.halfblanket.cloudly.data.drive

import android.webkit.MimeTypeMap

object MimeTypes {
    const val FOLDER = "application/vnd.google-apps.folder"
    const val SHORTCUT = "application/vnd.google-apps.shortcut"
    const val G_DOC = "application/vnd.google-apps.document"
    const val G_SHEET = "application/vnd.google-apps.spreadsheet"
    const val G_SLIDES = "application/vnd.google-apps.presentation"
    const val G_DRAWING = "application/vnd.google-apps.drawing"
    const val G_FORM = "application/vnd.google-apps.form"
    const val G_SITE = "application/vnd.google-apps.site"
    const val G_MAP = "application/vnd.google-apps.map"
    const val G_SCRIPT = "application/vnd.google-apps.script"
    const val G_JAM = "application/vnd.google-apps.jam"
    const val PDF = "application/pdf"

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val PNG = "image/png"
    const val JSON_SCRIPT = "application/vnd.google-apps.script+json"

    fun isGoogleNative(mime: String) = mime.startsWith("application/vnd.google-apps.")

    /**
     * How a Google-native file is exported for download / cross-account transfer.
     * [Export.mime] is the exported format; [Export.extension] is appended to the file name.
     * Returns null when Drive cannot export the type at all (forms, sites, shortcuts, folders, …).
     */
    data class Export(val mime: String, val extension: String)

    fun exportFor(mime: String): Export? = when (mime) {
        G_DOC -> Export(DOCX, "docx")
        G_SHEET -> Export(XLSX, "xlsx")
        G_SLIDES -> Export(PPTX, "pptx")
        G_DRAWING -> Export(PNG, "png")
        G_SCRIPT -> Export(JSON_SCRIPT, "json")
        else -> null
    }

    /** Google-native types that can be rendered as a PDF for in-app preview. */
    fun canExportPdf(mime: String) = mime in setOf(G_DOC, G_SHEET, G_SLIDES, G_DRAWING)

    /** When uploading an exported Office file into another account, convert it back to this type. */
    fun importTargetFor(exportedFrom: String): String? = when (exportedFrom) {
        G_DOC, G_SHEET, G_SLIDES -> exportedFrom
        else -> null
    }

    fun fromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun isText(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in setOf("application/json", "application/xml", "application/javascript", "application/x-sh",
                "application/x-yaml", "application/toml")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("txt", "md", "json", "xml", "csv", "log", "yml", "yaml", "kt", "java", "py", "js",
            "ts", "html", "css", "sh", "ini", "conf", "toml", "gradle", "sql", "c", "cpp", "h", "go", "rs", "swift")
    }
}
