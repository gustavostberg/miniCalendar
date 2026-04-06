package com.gustav.minicalendar

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("MiniCalAlarm", "AlarmReceiver.onReceive() called action=${intent.action}")

        val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.notification_default_event_title)
        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val eventStart = intent.getLongExtra(EXTRA_EVENT_START, -1L)
        val notificationId = notificationIdFor(eventId, eventStart)

        android.util.Log.d(
            "MiniCalAlarm",
            "extras: title=$title time=$time event_id=$eventId event_start=$eventStart notification_id=$notificationId"
        )

        when (intent.action) {
            ACTION_DISMISS -> {
                cancelNotification(context, notificationId)
            }

            ACTION_SNOOZE_5M -> {
                cancelNotification(context, notificationId)
                scheduleSnooze(context, title, time, eventId, eventStart, 5)
            }

            ACTION_SNOOZE_15M -> {
                cancelNotification(context, notificationId)
                scheduleSnooze(context, title, time, eventId, eventStart, 15)
            }

            else -> {
                NotificationHelper.ensureChannel(context)
                NotificationHelper.showNotification(
                    context = context,
                    title = title,
                    text = time,
                    eventId = eventId,
                    eventStart = eventStart,
                    notificationId = notificationId
                )

                // schemalägg nästa alarm direkt
                AlarmScheduler.scheduleNext(context)
            }
        }
    }

    private fun scheduleSnooze(
        context: Context,
        title: String,
        time: String,
        eventId: Long,
        eventStart: Long,
        minutes: Int
    ) {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_SHOW_ALARM
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_EVENT_START, eventStart)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdFor(eventId, eventStart) + 10_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )
        android.util.Log.d("MiniCalAlarm", "Scheduled snooze for $minutes min at $triggerAt")
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }

    companion object {
        const val ACTION_SHOW_ALARM = "com.gustav.minicalendar.action.SHOW_ALARM"
        const val ACTION_SNOOZE_5M = "com.gustav.minicalendar.action.SNOOZE_5M"
        const val ACTION_SNOOZE_15M = "com.gustav.minicalendar.action.SNOOZE_15M"
        const val ACTION_DISMISS = "com.gustav.minicalendar.action.DISMISS"

        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_EVENT_START = "event_start"

        fun notificationIdFor(eventId: Long, eventStart: Long): Int {
            val seed = "$eventId:$eventStart"
            return seed.hashCode()
        }
    }
}
