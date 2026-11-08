package com.gustav.minicalendar

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
        const val COLUMN_EVENT_ID = "eventId"
        const val EXTRA_VERSION = "integrationVersion"
        const val EXTRA_CALENDAR_ACCESS = "calendarAccess"
        const val QUERY_START = "startMillis"
        const val QUERY_END = "endMillis"
        const val QUERY_LIMIT = "limit"

        val EVENTS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_EVENTS")
    }

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_EVENTS, MATCH_EVENTS)
        }

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith("read_calendar_") == true) {
            context?.contentResolver?.notifyChange(EVENTS_URI, null, false)
        }
    }

    override fun onCreate(): Boolean {
        context?.getSharedPreferences("minicalendar", Context.MODE_PRIVATE)
            ?.registerOnSharedPreferenceChangeListener(preferencesListener)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        return when (uriMatcher.match(uri)) {
            MATCH_EVENTS -> queryEvents(uri)
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

    private fun queryEvents(uri: Uri): Cursor {
        val ctx = context ?: return emptyCursor()
        if (
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return emptyCursor().apply { extras = metadata(false) }
        }

        val repository = CalendarRepository(ctx)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = uri.getQueryParameter(QUERY_START)?.let { requireNotNull(it.toLongOrNull()) }
            ?: today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = uri.getQueryParameter(QUERY_END)?.let { requireNotNull(it.toLongOrNull()) }
            ?: today.plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()
        require(end > start && end - start <= 32L * 86_400_000L) { "Invalid event window" }
        val limit = uri.getQueryParameter(QUERY_LIMIT)?.let { requireNotNull(it.toIntOrNull()).coerceIn(1, 512) } ?: 4
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate().plusDays(1)
        val enabledCalendarIds = CalendarSelection.enabledIds(ctx.getSharedPreferences("minicalendar", Context.MODE_PRIVATE))
        val events =
            repository.getEvents(
                startMillis = minOf(start, startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()),
                endMillis = maxOf(end, endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()),
                enabledCalendarIds = enabledCalendarIds,
            )
                .filter { event -> LauncherEventWindow.includes(event, start, end, now, zone) }
                .sortedWith(
                    compareBy<CalendarRepository.Event>({ it.startMillis }, { it.title.lowercase(Locale.getDefault()) }),
                )
                .take(limit)

        val cursor = emptyCursor()
        events.forEach { event ->
            cursor.addRow(
                arrayOf(
                    event.id,
                    event.title,
                    event.startMillis,
                    event.endMillis,
                    if (event.isAllDay) 1 else 0,
                    event.location,
                    event.id,
                ),
            )
        }
        cursor.extras = metadata(true)
        cursor.setNotificationUri(ctx.contentResolver, EVENTS_URI)
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
                COLUMN_EVENT_ID,
            ),
        )
    }

    private fun metadata(calendarAccess: Boolean) = Bundle().apply {
        putInt(EXTRA_VERSION, 2)
        putBoolean(EXTRA_CALENDAR_ACCESS, calendarAccess)
    }
}
