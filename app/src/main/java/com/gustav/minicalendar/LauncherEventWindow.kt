package com.gustav.minicalendar

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal object LauncherEventWindow {
    fun includes(event: CalendarRepository.Event, start: Long, end: Long, now: Long, zone: ZoneId): Boolean {
        if (!event.isAllDay) return event.startMillis < end && event.endMillis > maxOf(start, now)
        val firstDate = Instant.ofEpochMilli(maxOf(start, now)).atZone(zone).toLocalDate()
        val endTime = Instant.ofEpochMilli(end).atZone(zone)
        val endDate = if (endTime.toLocalTime() == LocalTime.MIDNIGHT) endTime.toLocalDate() else endTime.toLocalDate().plusDays(1)
        val eventStart = Instant.ofEpochMilli(event.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val eventEnd = Instant.ofEpochMilli(event.endMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return eventStart.isBefore(endDate) && eventEnd.isAfter(firstDate)
    }
}
