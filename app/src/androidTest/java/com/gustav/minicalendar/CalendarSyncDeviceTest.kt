package com.gustav.minicalendar

import android.content.ContentUris
import android.content.ContentResolver
import android.accounts.Account
import android.os.Bundle
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Explicitly invoked probe for checking a test event across the phone and server. */
@RunWith(AndroidJUnit4::class)
class CalendarSyncDeviceTest {
    @Test
    fun probe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val calendar = args.getString("calendarId")?.toLong() ?: return
        val title = requireNotNull(args.getString("testTitle"))
        require(title.startsWith("MiniCalendar TEST "))
        val context = instrumentation.targetContext
        val repository = CalendarRepository(context)
        val start = LocalDate.now().plusDays(2).atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val mode = args.getString("mode") ?: "create"
        if (mode == "sync") {
            val calendarInfo = repository.getCalendars().single { it.id == calendar }
            ContentResolver.requestSync(Account(calendarInfo.accountName, calendarInfo.accountType), CalendarContract.AUTHORITY,
                Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            return
        }
        if (mode == "create" || mode == "createAndCancel") {
            val uri = repository.insertTimedEvent(calendar, title, start, start + 3_600_000L,
                description = "<p>Test: \u00e5\u00e4\u00f6 &amp; Teams</p>", rrule = "FREQ=DAILY;COUNT=5")
            assertNotNull(uri)
            if (mode == "createAndCancel") {
                val instances = repository.getEvents(start, start + 10L * 86_400_000L, setOf(calendar))
                    .filter { it.title == title }.sortedBy { it.startMillis }
                assertTrue(repository.deleteSingleOccurrence(instances[1]))
            }
        } else {
            val events = repository.getEvents(start - 86_400_000L, start + 35L * 86_400_000L, setOf(calendar))
                .filter { it.title == title }.sortedBy { it.startMillis }
            assertTrue("Test fixture not found", events.isNotEmpty())
            val event = events[args.getString("index")?.toInt() ?: 0]
            when (mode) {
                "deleteOne" -> assertTrue(repository.deleteSingleOccurrence(event))
                "deleteFuture" -> assertTrue(repository.deleteThisAndFuture(event))
                "deleteSeries" -> assertTrue(repository.deleteSeries(event))
                "edit" -> assertTrue(repository.updateTimedEvent(event.id, calendar, title,
                    event.startMillis, event.endMillis, location = "MiniCalendar sync test", rrule = event.rrule,
                    description = "<p>Updated test: \u00e5\u00e4\u00f6 &amp; Teams</p>"))
                "read" -> Unit
                else -> error("Unknown test mode")
            }
        }
        val report = StringBuilder()
        context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf("_id", "title", "dtstart", "dtend", "duration", "rrule", "exdate", "dirty", "deleted", "_sync_id", "original_id", "originalInstanceTime", "eventStatus"),
            "calendar_id=? AND title=?", arrayOf(calendar.toString(), title), null)!!.use { cursor ->
            while (cursor.moveToNext()) {
                report.appendLine(cursor.columnNames.indices.joinToString { "${cursor.columnNames[it]}=${cursor.getString(it)}" })
            }
        }
        report.appendLine("INSTANCES=" + repository.getEvents(start - 86_400_000L, start + 35L * 86_400_000L, setOf(calendar))
            .filter { it.title == title }.map { "${it.id}:${it.startMillis}:${it.endMillis}" })
        instrumentation.sendStatus(0, Bundle().apply { putString("probe", report.toString()) })
    }
}
