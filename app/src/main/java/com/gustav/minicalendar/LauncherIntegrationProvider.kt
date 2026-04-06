package com.gustav.minicalendar

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

class LauncherIntegrationProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.gustav.minicalendar.launcher"
        private const val PATH_EVENTS = "events"
        private const val MATCH_EVENTS = 1

        const val COLUMN_ID = "_id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_START_MILLIS = "startMillis"
        const val COLUMN_END_MILLIS = "endMillis"
        const val COLUMN_IS_ALL_DAY = "isAllDay"
        const val COLUMN_LOCATION = "location"

        val EVENTS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_EVENTS")
    }

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_EVENTS, MATCH_EVENTS)
        }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return when (uriMatcher.match(uri)) {
            MATCH_EVENTS -> queryEvents()
            else -> throw IllegalArgumentException("Unknown uri: $uri")
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            MATCH_EVENTS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_EVENTS"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun queryEvents(): Cursor {
        val ctx = context ?: return emptyCursor()
        if (
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return emptyCursor()
        }

        val repository = CalendarRepository(ctx)
        val enabledCalendarIds = loadEnabledCalendarIds(ctx, repository)
        val todayStart = Calendar.getInstance().startOfDayMillis()
        val now = System.currentTimeMillis()
        val horizon = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 14) }.timeInMillis
        val events =
            repository.getEvents(
                startMillis = todayStart,
                endMillis = horizon,
                enabledCalendarIds = enabledCalendarIds.takeIf { it.isNotEmpty() },
            )
                .filter { event -> event.endMillis > now || (event.isAllDay && event.endMillis > todayStart) }
                .sortedWith(
                    compareBy<CalendarRepository.Event>({ it.startMillis }, { it.title.lowercase(Locale.getDefault()) }),
                )
                .take(4)

        val cursor = emptyCursor()
        events.forEachIndexed { index, event ->
            cursor.addRow(
                arrayOf(
                    index.toLong(),
                    event.title,
                    event.startMillis,
                    event.endMillis,
                    if (event.isAllDay) 1 else 0,
                    event.location,
                ),
            )
        }
        return cursor
    }

    private fun emptyCursor(): MatrixCursor {
        return MatrixCursor(
            arrayOf(
                COLUMN_ID,
                COLUMN_TITLE,
                COLUMN_START_MILLIS,
                COLUMN_END_MILLIS,
                COLUMN_IS_ALL_DAY,
                COLUMN_LOCATION,
            ),
        )
    }

    private fun loadEnabledCalendarIds(
        context: Context,
        repository: CalendarRepository,
    ): Set<Long> {
        val prefs = context.getSharedPreferences("minicalendar", Context.MODE_PRIVATE)
        val calendars = repository.getCalendars()
        val savedReadIds =
            prefs.all
                .filterKeys { it.startsWith("read_calendar_") }
                .mapNotNull { (key, value) ->
                    if (value == true) key.removePrefix("read_calendar_").toLongOrNull() else null
                }
                .toSet()

        return if (savedReadIds.isNotEmpty()) {
            calendars.map { it.id }.filter { it in savedReadIds }.toSet()
        } else {
            calendars.map { it.id }.toSet()
        }
    }

    private fun Calendar.startOfDayMillis(): Long {
        return apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
