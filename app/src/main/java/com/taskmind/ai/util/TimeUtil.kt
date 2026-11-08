package com.taskmind.ai.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object TimeUtil {
    val zone: ZoneId get() = ZoneId.systemDefault()

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dowFmt = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
    private val shortDateFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val yearDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val fullFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a", Locale.ENGLISH)
    private val headerFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    val isoDay: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    val isoMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

    fun now(): LocalDateTime = LocalDateTime.now(zone)
    fun today(): LocalDate = LocalDate.now(zone)
    fun toMillis(ldt: LocalDateTime): Long = ldt.atZone(zone).toInstant().toEpochMilli()
    fun toLdt(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    fun toDate(ms: Long): LocalDate = toLdt(ms).toLocalDate()
    fun startOfDay(date: LocalDate = today()): Long = toMillis(date.atStartOfDay())
    fun endOfDay(date: LocalDate = today()): Long = toMillis(date.plusDays(1).atStartOfDay()) - 1

    fun headerDate(): String = today().format(headerFmt)

    fun greeting(hour: Int = now().hour): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Working late"
    }

    fun dayLabel(date: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today(), date)
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff == -1L -> "Yesterday"
            diff in 2..6 -> date.format(dowFmt)
            date.year == today().year -> date.format(shortDateFmt)
            else -> date.format(yearDateFmt)
        }
    }

    fun formatDue(ms: Long, hasTime: Boolean): String {
        val ldt = toLdt(ms)
        val day = dayLabel(ldt.toLocalDate())
        return if (hasTime) "$day, ${ldt.format(timeFmt)}" else day
    }

    fun formatTime(ms: Long): String = toLdt(ms).format(timeFmt)
    fun formatTime(h: Int, m: Int): String = LocalDateTime.of(2000, 1, 1, h, m).format(timeFmt)
    fun formatFull(ms: Long): String = toLdt(ms).format(fullFmt)

    fun overdueBy(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val mins = (nowMs - ms) / 60_000
        return when {
            mins < 60 -> "${mins.coerceAtLeast(1)}m overdue"
            mins < 60 * 24 -> "${mins / 60}h overdue"
            else -> "${mins / (60 * 24)}d overdue"
        }
    }

    fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun relative(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val mins = (nowMs - ms) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d ago"
            else -> formatDue(ms, false)
        }
    }
}
