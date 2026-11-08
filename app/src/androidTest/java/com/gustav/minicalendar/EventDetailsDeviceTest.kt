package com.gustav.minicalendar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EventDetailsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = CalendarRepository(context)
    private val ids = mutableListOf<Long>()
    private val start = LocalDate.now().plusDays(2).atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val calendarId by lazy {
        InstrumentationRegistry.getArguments().getString("calendarId")?.toLong()
            ?: repository.getCalendars().first { it.accountType == "LOCAL" }.id
    }

    @After
    fun tearDown() {
        ids.forEach { repository.deleteEvent(it) }
    }

    @Test
    fun eventDetailsShowReadableTextAtPhoneSize() {
        val event = create(description = "<p>Tr\u00e4ning &amp; m\u00f6te: \u00e5\u00e4\u00f6</p><p>Ta med kl\u00e4der.</p>\nSYNC_KEY:123456\nSYNC_HASH:test\nOrganizer: example@example.com")
        open(event).use { scenario ->
            scenario.onActivity { activity ->
                val text = activity.findViewById<TextView>(R.id.body).text.toString()
                assertTrue(text.contains("Tr\u00e4ning & m\u00f6te: \u00e5\u00e4\u00f6"))
                assertFalse(text.contains("SYNC_KEY"))
                assertFalse(text.contains("<p>"))
                assertFalse(text.contains("\u00c3"))
                val root = activity.window.decorView
                assertTrue(root.width > 0 && root.height > 0)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "event-details-test.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun keyboardDeletesSingleEventAfterConfirmation() {
        val event = create()
        open(event).use { scenario ->
            scenario.onActivity { activity ->
                press(activity, KeyEvent.KEYCODE_D)
                assertTrue(activity.findViewById<TextView>(R.id.body).text.toString()
                    .contains(activity.getString(R.string.label_delete_confirm, event.title)))
                press(activity, KeyEvent.KEYCODE_D)
            }
            assertTrue(instances(event).isEmpty())
        }
    }

    @Test
    fun keyboardDeletesOneOccurrence() = deleteRecurring(KeyEvent.KEYCODE_1, 3)

    @Test
    fun keyboardDeletesThisAndFuture() = deleteRecurring(KeyEvent.KEYCODE_2, 1)

    @Test
    fun keyboardDeletesWholeSeries() = deleteRecurring(KeyEvent.KEYCODE_3, 0)

    private fun deleteRecurring(choice: Int, remaining: Int) {
        val event = create(rule = "FREQ=DAILY;COUNT=4")
        val second = instances(event)[1]
        open(second).use { scenario ->
            scenario.onActivity { activity ->
                press(activity, KeyEvent.KEYCODE_D)
                assertTrue(activity.findViewById<TextView>(R.id.body).text.toString()
                    .contains(activity.getString(R.string.label_delete_recurring_event)))
                press(activity, choice)
            }
            assertEquals(remaining, instances(event).size)
        }
    }

    private fun create(rule: String? = null, description: String = ""): CalendarRepository.Event {
        val id = repository.parseEventId(repository.insertTimedEvent(calendarId,
            "MiniCalendar TEST UI ${UUID.randomUUID()}", start, start + 3_600_000L,
            description = description, rrule = rule))!!
        ids.add(id)
        return repository.getEvents(start, start + 7L * 86_400_000L, setOf(calendarId)).first { it.id == id }
    }

    private fun instances(event: CalendarRepository.Event) =
        repository.getEvents(start, start + 7L * 86_400_000L, setOf(calendarId)).filter { it.id == event.id }

    private fun open(event: CalendarRepository.Event): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(context, MainActivity::class.java).apply {
            putExtra("open_event", true)
            putExtra(AlarmReceiver.EXTRA_EVENT_ID, event.id)
            putExtra(AlarmReceiver.EXTRA_EVENT_START, event.startMillis)
        })

    private fun press(activity: MainActivity, key: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
}
