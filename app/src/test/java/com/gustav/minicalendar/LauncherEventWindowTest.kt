package com.gustav.minicalendar

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class LauncherEventWindowTest {
    private val day = LocalDate.of(2026, 11, 8)
    private fun allDay(date: LocalDate) = CalendarRepository.Event(1, 1, "Test", true,
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

    private fun includes(event: CalendarRepository.Event, zoneName: String, hour: Int, minute: Int = 0): Boolean {
        val zone = ZoneId.of(zoneName)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        return LauncherEventWindow.includes(event, start, end, now, zone)
    }

    @Test fun yesterdayAllDayDoesNotLeakIntoSwedishMidnight() {
        assertFalse(includes(allDay(day.minusDays(1)), "Europe/Stockholm", 0, 15))
    }

    @Test fun todayAllDayRemainsVisibleLateInWesternTimezones() {
        assertTrue(includes(allDay(day), "America/Los_Angeles", 23, 45))
    }

    @Test fun endOfWindowIsExclusiveForAllDayEvents() {
        assertFalse(includes(allDay(day.plusDays(14)), "Europe/Stockholm", 10))
        assertTrue(includes(allDay(day.plusDays(13)), "Europe/Stockholm", 10))
    }

    @Test fun ongoingTimedEventRemainsAndFinishedEventDisappears() {
        val zone = ZoneId.of("Europe/Stockholm")
        val now = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val event = CalendarRepository.Event(1, 1, "Test", false, now - 3_600_000, now + 60_000)
        assertTrue(includes(event, zone.id, 10))
        assertFalse(includes(event.copy(endMillis = now), zone.id, 10))
    }

    @Test fun multiDayAllDaySpansLocalDatesAcrossDst() {
        val event = allDay(day.minusDays(10)).copy(endMillis = allDay(day.plusDays(1)).endMillis)
        assertTrue(includes(event, "America/Los_Angeles", 23))
    }
}
