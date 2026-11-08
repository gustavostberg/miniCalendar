package com.gustav.minicalendar

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val repository = CalendarRepository(context)
    private val prefix = "MiniCalendar TEST ${UUID.randomUUID()} "
    private var calendarId = -1L
    private var ownsCalendar = false
    private val firstDay = LocalDate.now().plusDays(2)
    private val start = firstDay.atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val end = start + 3_600_000L
    private val rangeEnd = start + 35L * DAY

    @Before
    fun setUp() {
        val configured = InstrumentationRegistry.getArguments().getString("calendarId")?.toLong()
        if (configured != null) {
            calendarId = configured
        } else {
            val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "minicalendar-device-test")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            calendarId = ContentUris.parseId(resolver.insert(uri, ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, "minicalendar-device-test")
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, prefix)
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, prefix)
                put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
                put(CalendarContract.Calendars.OWNER_ACCOUNT, "minicalendar-device-test")
                put(CalendarContract.Calendars.VISIBLE, 1)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            })!!)
            ownsCalendar = true
        }
    }

    @After
    fun tearDown() {
        if (calendarId < 0) return
        resolver.delete(CalendarContract.Events.CONTENT_URI,
            "calendar_id=? AND title LIKE ?", arrayOf(calendarId.toString(), "$prefix%"))
        if (ownsCalendar) {
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
                .buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "minicalendar-device-test")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build()
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun timedCreateEditDeleteIsMarkedForSync() {
        val rawDescription = "<p>Meeting &amp; caf\u00e9: \u00e5\u00e4\u00f6</p>"
        val id = createTimed(description = rawDescription)
        assertEquals(1L, number(id, CalendarContract.Events.DIRTY))
        assertEquals(rawDescription, text(id, CalendarContract.Events.DESCRIPTION))
        assertEquals(1, events(id).size)
        assertTrue(repository.updateTimedEvent(id, calendarId, prefix + "edited", start, end,
            description = rawDescription))
        assertEquals(prefix + "edited", events(id).single().title)
        assertEquals(1L, number(id, CalendarContract.Events.DIRTY))
        assertTrue(repository.deleteEvent(id))
        assertTrue(events(id).isEmpty())
    }

    @Test
    fun allDayCreateEditDeletePreservesUtcBoundaries() {
        val day = firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val id = repository.parseEventId(repository.insertAllDayEvent(calendarId, prefix + "all-day", day, day + DAY))!!
        assertEquals(day, events(id).single().startMillis)
        assertTrue(repository.updateAllDayEvent(id, calendarId, prefix + "all-day edited", day, day + 2 * DAY))
        assertEquals(day + 2 * DAY, events(id).single().endMillis)
        assertTrue(repository.deleteEvent(id))
        assertTrue(events(id).isEmpty())
    }

    @Test
    fun recurringCreateEditUsesDurationAndKeepsOriginalStart() {
        val id = createTimed("FREQ=WEEKLY;COUNT=4")
        assertNull(text(id, CalendarContract.Events.DTEND))
        assertEquals("PT3600S", text(id, CalendarContract.Events.DURATION))
        val later = events(id)[1]
        assertTrue(repository.updateTimedEvent(id, calendarId, prefix + "weekly edited",
            later.startMillis, later.endMillis, rrule = "FREQ=WEEKLY;COUNT=4"))
        assertEquals(4, events(id).size)
        assertEquals(start, events(id).first().startMillis)
        assertEquals(3_600_000L, events(id).first().endMillis - events(id).first().startMillis)
    }

    @Test
    fun deletingOneWeeklyOccurrenceKeepsTheOtherThree() {
        val id = createTimed("FREQ=WEEKLY;COUNT=4")
        val before = events(id)
        assertEquals(4, before.size)
        assertTrue(repository.deleteSingleOccurrence(before[1]))
        assertEquals(before.filterIndexed { i, _ -> i != 1 }.map { it.startMillis }, events(id).map { it.startMillis })
        assertTrue(repository.deleteSingleOccurrence(events(id).first()))
        assertEquals(2, events(id).size)
    }

    @Test
    fun deletingOneAllDayOccurrenceKeepsTheOtherDays() {
        val day = firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val id = repository.parseEventId(repository.insertAllDayEvent(calendarId, prefix + "daily all-day",
            day, day + DAY, rrule = "FREQ=DAILY;COUNT=4"))!!
        awaitSyncedIdentity(id)
        val before = events(id)
        assertEquals(4, before.size)
        assertTrue(repository.deleteSingleOccurrence(before[1]))
        assertEquals(before.filterIndexed { i, _ -> i != 1 }.map { it.startMillis }, events(id).map { it.startMillis })
        resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.ORIGINAL_ALL_DAY),
            "original_id=? AND eventStatus=?", arrayOf(id.toString(), CalendarContract.Events.STATUS_CANCELED.toString()), null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun deletingUnsyncedAllDayOccurrenceKeepsTheOtherDays() {
        val day = firstDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val id = repository.parseEventId(repository.insertAllDayEvent(calendarId, prefix + "unsynced all-day",
            day, day + DAY, rrule = "FREQ=DAILY;COUNT=4"))!!
        val before = events(id)
        assertTrue(repository.deleteSingleOccurrence(before[1]))
        assertEquals(before.filterIndexed { i, _ -> i != 1 }.map { it.startMillis }, events(id).map { it.startMillis })
    }

    @Test
    fun deletingThisAndFutureCapsCountAndDeletesLaterExceptions() {
        val id = createTimed("FREQ=DAILY;COUNT=6")
        val before = events(id)
        assertEquals(6, before.size)
        addMovedException(id, before[4])
        assertTrue(repository.deleteThisAndFuture(before[2]))
        assertEquals(before.take(2).map { it.startMillis }, eventsInTest().map { it.startMillis })
        assertEquals(1L, number(id, CalendarContract.Events.DIRTY))
    }

    @Test
    fun deletingEntireSeriesAlsoDeletesMovedExceptions() {
        val id = createTimed("FREQ=WEEKLY;COUNT=4")
        val movedId = addMovedException(id, events(id)[1])
        assertEquals(4, eventsInTest().size)
        val moved = eventsInTest().single { it.id == movedId }
        assertTrue(moved.isRecurring)
        assertTrue(repository.deleteSeries(moved))
        assertTrue(eventsInTest().isEmpty())
    }

    @Test
    fun deletingMovedOccurrenceKeepsRestOfSeries() {
        val id = createTimed("FREQ=WEEKLY;COUNT=4")
        val before = events(id)
        val movedId = addMovedException(id, before[1])
        assertTrue(repository.deleteSingleOccurrence(eventsInTest().single { it.id == movedId }))
        assertEquals(before.filterIndexed { i, _ -> i != 1 }.map { it.startMillis }, eventsInTest().map { it.startMillis })
    }

    @Test
    fun deletingFutureFromMovedOccurrenceUsesOriginalStart() {
        val id = createTimed("FREQ=DAILY;COUNT=6")
        val before = events(id)
        val movedId = addMovedException(id, before[2])
        assertTrue(repository.deleteThisAndFuture(eventsInTest().single { it.id == movedId }))
        assertEquals(before.take(2).map { it.startMillis }, eventsInTest().map { it.startMillis })
    }

    @Test
    fun staleOccurrenceCannotDeleteEntireSeries() {
        val id = createTimed("FREQ=WEEKLY;COUNT=4")
        val before = events(id)
        assertFalse(repository.deleteSingleOccurrence(before[1].copy(id = Long.MAX_VALUE)))
        assertFalse(repository.deleteThisAndFuture(before[1].copy(id = Long.MAX_VALUE)))
        assertEquals(before.map { it.startMillis }, events(id).map { it.startMillis })
    }

    @Test
    fun deletingLegacyOccurrenceRepairsMissingDuration() {
        assumeTrue(ownsCalendar)
        val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "minicalendar-device-test")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val id = ContentUris.parseId(resolver.insert(uri, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, prefix + "legacy")
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=4")
            put(CalendarContract.Events._SYNC_ID, "SYNC_ERROR: Missing end time.")
        })!!)
        val before = events(id)
        assertEquals(4, before.size)
        assertTrue(repository.deleteSingleOccurrence(before[1]))
        assertEquals(3, events(id).size)
        assertNotNull(text(id, CalendarContract.Events.DURATION))
        assertTrue(repository.deleteThisAndFuture(events(id)[1]))
        assertEquals(1, events(id).size)
    }

    @Test
    fun deletingFirstAndFutureDeletesWholeSeries() {
        val id = createTimed("FREQ=MONTHLY;COUNT=2")
        assertTrue(repository.deleteThisAndFuture(events(id).first()))
        assertTrue(eventsInTest().isEmpty())
    }

    private fun createTimed(rule: String? = null, description: String = ""): Long {
        val uri = repository.insertTimedEvent(calendarId, prefix + "timed", start, end,
            description = description, rrule = rule)
        assertNotNull("Insert failed", uri)
        return repository.parseEventId(uri)!!
    }

    private fun addMovedException(id: Long, event: CalendarRepository.Event): Long =
        awaitSyncedIdentity(id).let { ContentUris.parseId(resolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, id),
            ContentValues().apply {
                put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.startMillis)
                put(CalendarContract.Events.DTSTART, event.startMillis + 1_800_000L)
            })!!) }

    private fun awaitSyncedIdentity(id: Long) {
        if (ownsCalendar) {
            // Simulate a downloaded row only in the isolated local test calendar.
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id).buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "minicalendar-device-test")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            resolver.update(uri, ContentValues().apply { put(CalendarContract.Events._SYNC_ID, "test-$id") }, null, null)
        } else {
            val calendar = repository.getCalendars().single { it.id == calendarId }
            android.content.ContentResolver.requestSync(android.accounts.Account(calendar.accountName, calendar.accountType),
                CalendarContract.AUTHORITY, android.os.Bundle().apply {
                    putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            while (text(id, CalendarContract.Events._SYNC_ID).isNullOrBlank() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(500)
            }
            assertFalse("Google sync did not finish", text(id, CalendarContract.Events._SYNC_ID).isNullOrBlank())
        }
    }

    private fun eventsInTest() = repository.getEvents(start - DAY, rangeEnd, setOf(calendarId))
        .filter { it.title.startsWith(prefix) }.sortedBy { it.startMillis }

    private fun events(id: Long) = eventsInTest().filter { it.id == id }

    private fun text(id: Long, column: String): String? = resolver.query(
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(column), null, null, null)!!.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    private fun number(id: Long, column: String) = text(id, column)?.toLong()

    companion object {
        private const val DAY = 86_400_000L
    }
}
