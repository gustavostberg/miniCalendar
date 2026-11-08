package com.gustav.minicalendar

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlarmScheduler {

    private const val PREFS_NAME = "minicalendar"
    private const val CALENDAR_CHANGE_JOB_ID = 2101
    private const val WATCHDOG_REQUEST_CODE = 2102
    private const val WATCHDOG_INTERVAL_MILLIS = 15L * 60L * 1000L

    fun scheduleNext(context: Context) {

        android.util.Log.d("MiniCalAlarm", "scheduleNext() called")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("alarm_engine_enabled", false)) {
            cancelAll(context)
            cancelCalendarChangeWatcher(context)
            cancelWatchdog(context)
            return
        }

        scheduleCalendarChangeWatcher(context)
        scheduleWatchdog(context)

        val fallbackMinutes = prefs.getInt("alarm_engine_fallback_minutes", 10)

        val enabledCalendarIds = CalendarSelection.enabledIds(prefs)

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

    fun scheduleCalendarChangeWatcher(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("alarm_engine_enabled", false)) {
            cancelCalendarChangeWatcher(context)
            return
        }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val serviceName = ComponentName(context, CalendarChangeJobService::class.java)
        val jobInfo = JobInfo.Builder(CALENDAR_CHANGE_JOB_ID, serviceName)
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Events.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Reminders.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Calendars.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .setTriggerContentUpdateDelay(5_000L)
            .setTriggerContentMaxDelay(60_000L)
            .build()

        jobScheduler.schedule(jobInfo)
    }

    private fun cancelCalendarChangeWatcher(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(CALENDAR_CHANGE_JOB_ID)
    }

    private fun scheduleWatchdog(context: Context) {
        val intent = Intent(context, AlarmMaintenanceReceiver::class.java).apply {
            action = AlarmMaintenanceReceiver.ACTION_REFRESH_ALARMS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MILLIS,
            pendingIntent
        )
    }

    private fun cancelWatchdog(context: Context) {
        val intent = Intent(context, AlarmMaintenanceReceiver::class.java).apply {
            action = AlarmMaintenanceReceiver.ACTION_REFRESH_ALARMS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
    }
}
