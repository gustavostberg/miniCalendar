package com.gustav.minicalendar

import android.content.SharedPreferences

object CalendarSelection {
    // null means no selection has been configured; an empty set means all are off.
    fun enabledIds(prefs: SharedPreferences): Set<Long>? {
        val entries = prefs.all.filterKeys { it.startsWith("read_calendar_") }
        if (entries.isEmpty()) return null
        return entries.mapNotNull { (key, value) ->
            if (value == true) key.removePrefix("read_calendar_").toLongOrNull() else null
        }.toSet()
    }
}
