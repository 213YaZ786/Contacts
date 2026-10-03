package com.yaz.contacts.core.contacts

import android.text.format.DateFormat
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A date as contacts keep it: a full date, or a day of the year with no year. */
data class EventDate(val year: Int?, val month: Int, val day: Int) {

    /** How Android stores it: YYYY-MM-DD, or --MM-DD without a year. */
    fun stored(): String = if (year != null) "%04d-%02d-%02d".format(year, month, day) else "--%02d-%02d".format(month, day)

    /** The next time it comes, today included. */
    fun next(today: LocalDate): LocalDate {
        val md = MonthDay.of(month, day)
        val thisYear = if (md.isValidYear(today.year)) md.atYear(today.year) else LocalDate.of(today.year, 3, 1)
        return if (thisYear.isBefore(today)) {
            val y = today.year + 1
            if (md.isValidYear(y)) md.atYear(y) else LocalDate.of(y, 3, 1)
        } else thisYear
    }

    /** Days until it comes again, 0 today. */
    fun daysUntil(today: LocalDate): Long = ChronoUnit.DAYS.between(today, next(today))

    /** The age reached on its next coming, when the year is known. */
    fun ageNext(today: LocalDate): Int? = year?.let { next(today).year - it }?.takeIf { it in 0..150 }
}

object Dates {

    /**
     * What Android and the sync accounts write: 1990-03-12, --03-12,
     * 19900312, 1990-03-12T00:00:00Z, 12.03.1990 or 03/12/1990 from older
     * phones. Null for anything else.
     */
    fun parse(text: String): EventDate? {
        val t = text.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m -> return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        Regex("^--(\\d{1,2})-?(\\d{1,2})$").find(t)?.let { m -> return valid(null, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(t)?.let { m -> return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        Regex("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").find(t)?.let { m -> return valid(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").find(t)?.let { m -> return valid(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        return null
    }

    private fun valid(year: Int?, month: Int, day: Int): EventDate? {
        if (month !in 1..12 || day !in 1..31) return null
        if (year != null && year !in 1..9999) return null
        // 29 February with no year is a real date; with a year, only in a leap year.
        if (!MonthDay.of(month, 1).let { day <= it.month.maxLength() }) return null
        if (year != null && runCatching { LocalDate.of(year, month, day) }.isFailure) return null
        return EventDate(year, month, day)
    }

    /** The date in the phone's own words: "12 March 1990", "March 12" with no year. */
    fun spoken(date: EventDate, locale: Locale = Locale.getDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (date.year != null) "yMMMMd" else "MMMMd")
        val day = LocalDate.of(date.year ?: 2000, date.month, date.day)
        return DateTimeFormatter.ofPattern(pattern, locale).format(day)
    }
}
