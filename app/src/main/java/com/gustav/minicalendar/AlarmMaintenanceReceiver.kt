package com.gustav.minicalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmMaintenanceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("MiniCalAlarm", "AlarmMaintenanceReceiver.onReceive() action=${intent.action}")
        AlarmScheduler.scheduleNext(context.applicationContext)
    }

    companion object {
        const val ACTION_REFRESH_ALARMS = "com.gustav.minicalendar.action.REFRESH_ALARMS"
    }
}
