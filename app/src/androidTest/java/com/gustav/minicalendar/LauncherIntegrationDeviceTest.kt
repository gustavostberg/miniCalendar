package com.gustav.minicalendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LauncherIntegrationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("minicalendar", Context.MODE_PRIVATE)
    private val repository = CalendarRepository(context)
    private val name = "MiniCalendar WIDGET TEST ${UUID.randomUUID()}"
    private var savedSelection = emptyMap<String, Boolean>()
    private var calendarId = -1L
    private val start = System.currentTimeMillis() + 3_600_000L
    private val localAccount = "minicalendar-widget-test"

    @Before fun setUp() {
        savedSelection = prefs.all.filterKeys { it.startsWith("read_calendar_") }.mapValues { it.value == true }
        calendarId = ContentUris.parseId(resolver.insert(calendarUri(), ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, localAccount)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, localAccount)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        })!!)
        prefs.edit().apply {
            savedSelection.keys.forEach { putBoolean(it, false) }
            putBoolean("read_calendar_$calendarId", true)
        }.commit()
    }

    @After fun tearDown() {
        if (calendarId >= 0) {
            resolver.delete(CalendarContract.Events.CONTENT_URI, "calendar_id=?", arrayOf(calendarId.toString()))
            resolver.delete(ContentUris.withAppendedId(calendarUri(), calendarId), null, null)
        }
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("read_calendar_") }.forEach { remove(it) }
            savedSelection.forEach { (key, value) -> putBoolean(key, value) }
        }.commit()
        AlarmScheduler.scheduleNext(context)
    }

    @Test fun expandedFeedReturnsMoreThanFourAndRealEventIds() {
        val ids = (0..7).map { create(it) }.toSet()
        resolver.query(LauncherIntegrationProvider.EVENTS_URI, null, null, null, null)!!.use { assertEquals(4, it.count) }
        query().use {
            assertEquals(2, it.extras.getInt(LauncherIntegrationProvider.EXTRA_VERSION))
            assertTrue(it.extras.getBoolean(LauncherIntegrationProvider.EXTRA_CALENDAR_ACCESS))
            val actualIds = mutableSetOf<Long>()
            while (it.moveToNext()) actualIds.add(it.getLong(it.getColumnIndexOrThrow("eventId")))
            assertEquals(ids, actualIds)
        }
    }

    @Test fun hidingCalendarHidesItsEventsAndNotifiesLauncher() {
        create(0)
        query().use { assertEquals(1, it.count) }
        val latch = CountDownLatch(1)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { latch.countDown() }
        }
        resolver.registerContentObserver(LauncherIntegrationProvider.EVENTS_URI, true, observer)
        try {
            prefs.edit().putBoolean("read_calendar_$calendarId", false).commit()
            assertTrue("Launcher did not receive selection change", latch.await(3, TimeUnit.SECONDS))
            query().use { assertEquals(0, it.count) }
        } finally { resolver.unregisterContentObserver(observer) }
    }

    @Test fun allCalendarsOffStaysOffAfterMiniCalendarResume() {
        create(0)
        prefs.edit().putBoolean("read_calendar_$calendarId", false).commit()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertEquals(emptySet<Long>(), CalendarSelection.enabledIds(prefs))
            query().use { cursor -> assertEquals(0, cursor.count) }
        }
    }

    @Test fun deletedOccurrencesDoNotRemainInLauncherFeed() {
        val id = repository.parseEventId(repository.insertTimedEvent(calendarId, name, start, start + 60_000L,
            rrule = "FREQ=DAILY;COUNT=3"))!!
        query().use { assertEquals(3, it.count) }
        val event = repository.getEvents(start, start + 7L * 86_400_000L, setOf(calendarId))[1]
        assertTrue(repository.deleteSingleOccurrence(event))
        query().use { assertEquals(2, it.count) }
        assertTrue(repository.deleteEvent(id))
        query().use { assertEquals(0, it.count) }
    }

    @Test fun absentSelectionAndExplicitEmptySelectionAreDifferent() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("read_calendar_") }.forEach { remove(it) } }.commit()
        assertNull(CalendarSelection.enabledIds(prefs))
        prefs.edit().putBoolean("read_calendar_$calendarId", false).commit()
        assertEquals(emptySet<Long>(), CalendarSelection.enabledIds(prefs))
    }

    private fun create(index: Int): Long = repository.parseEventId(repository.insertTimedEvent(calendarId,
        "$name $index", start + index * 300_000L, start + index * 300_000L + 60_000L))!!

    private fun calendarUri() = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, localAccount)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun query() = resolver.query(LauncherIntegrationProvider.EVENTS_URI.buildUpon()
        .appendQueryParameter("startMillis", LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toString())
        .appendQueryParameter("endMillis", LocalDate.now().plusDays(14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli().toString())
        .appendQueryParameter("limit", "512").build(), null, null, null, null)!!
}
