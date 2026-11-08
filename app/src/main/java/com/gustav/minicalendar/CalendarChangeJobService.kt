package com.gustav.minicalendar

import android.app.job.JobParameters
import android.app.job.JobService

class CalendarChangeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        android.util.Log.d("MiniCalAlarm", "CalendarChangeJobService.onStartJob()")
        AlarmScheduler.scheduleNext(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
