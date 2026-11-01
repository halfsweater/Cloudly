package com.halfblanket.cloudly.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size - 1)
        val value = bytes / 1024.0.pow(exp)
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(Locale.getDefault(), pattern, value, units[exp])
    }

    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun relative(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return "—"
        val d = Duration.between(instant, now)
        return when {
            d.isNegative -> date(instant)
            d.toMinutes() < 1 -> "Just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            d.toDays() < 2 -> "Yesterday"
            d.toDays() < 7 -> "${d.toDays()} days ago"
            else -> date(instant)
        }
    }

    fun date(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(dateFmt) ?: "—"

    fun dateTime(instant: Instant?): String =
        instant?.atZone(ZoneId.systemDefault())?.format(dateTimeFmt) ?: "—"

    fun duration(millis: Long?): String {
        if (millis == null || millis <= 0) return "—"
        val s = millis / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun percent(used: Long, total: Long?): Float =
        if (total == null || total <= 0) 0f else (used.toFloat() / total).coerceIn(0f, 1f)
}
