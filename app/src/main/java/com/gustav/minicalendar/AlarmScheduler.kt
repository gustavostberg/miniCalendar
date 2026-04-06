package com.gustav.minicalendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlarmScheduler {

    private const val PREFS_NAME = "minicalendar"

    fun scheduleNext(context: Context) {

        android.util.Log.d("MiniCalAlarm", "scheduleNext() called")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("alarm_engine_enabled", false)) {
            cancelAll(context)
            return
        }

        val fallbackMinutes = prefs.getInt("alarm_engine_fallback_minutes", 10)

        val enabledCalendarIds = prefs.all
            .filterKeys { it.startsWith("read_calendar_") }
            .mapNotNull { (key, value) ->
                if (value == true) key.removePrefix("read_calendar_").toLongOrNull() else null
            }
            .toSet()
            .takeIf { it.isNotEmpty() }

        val now = System.currentTimeMillis()
        val horizon = now + 48L * 60L * 60L * 1000L

        val repo = CalendarRepository(context)
        val events = repo.getEvents(
            startMillis = now,
            endMillis = horizon,
            enabledCalendarIds = enabledCalendarIds
        )

        var bestEvent: CalendarRepository.Event? = null
        var bestAlarmTime: Long? = null

        android.util.Log.d("MiniCalAlarm", "events found=${events.size}")

        for (ev in events) {
            if (ev.isAllDay) continue

            val leadMinutes = ev.reminderMinutes ?: fallbackMinutes
            val alarmTime = ev.startMillis - leadMinutes * 60_000L

            if (alarmTime <= now) continue

            if (bestAlarmTime == null || alarmTime < bestAlarmTime!!) {
                bestAlarmTime = alarmTime
                bestEvent = ev
            }
        }

        if (bestEvent == null || bestAlarmTime == null) {
            cancelAll(context)
            return
        }

        val timeText = SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault())
            .format(Date(bestEvent.startMillis))

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("title", bestEvent.title)
            putExtra("time", timeText)
            putExtra("event_id", bestEvent.id)
            putExtra("event_start", bestEvent.startMillis)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {

            android.util.Log.d(
                "MiniCalAlarm",
                "canScheduleExactAlarms=${alarmManager.canScheduleExactAlarms()}"
            )

            if (!alarmManager.canScheduleExactAlarms()) {
                android.util.Log.w("MiniCalAlarm", "Exact alarms not allowed")
                return
            }
        }

        android.util.Log.d("MiniCalAlarm", "Scheduling '${bestEvent.title}' at $bestAlarmTime")

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            bestAlarmTime,
            pendingIntent
        )
    }

    fun cancelAll(context: Context) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }
}
