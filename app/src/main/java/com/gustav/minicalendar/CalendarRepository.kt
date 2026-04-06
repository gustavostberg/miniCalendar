package com.gustav.minicalendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Reads and writes calendar events via Android's CalendarContract.
 * Requires READ_CALENDAR (and WRITE_CALENDAR for inserts) permissions.
 */
class CalendarRepository(private val context: Context) {

    // ── Data model ────────────────────────────────────────────────────────────

    data class CalInfo(
        val id: Long,
        val name: String,
        val accountName: String,
        val accountType: String,
        val ownerAccount: String,   // e-postadress som används som ORGANIZER
        val color: Int,
        val visible: Boolean
    )

    data class Event(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val isAllDay: Boolean,
        val startMillis: Long,
        val endMillis: Long,    // instance start/end, exclusive for all-day
        val location: String = "",
        val description: String = "",
        val rrule: String? = null,
        val reminderMinutes: Int? = null,
        val originalStartMillis: Long? = null,   // master DTSTART from Events table
        val originalEndMillis: Long? = null      // master DTEND from Events table
    ) {
        val startMin: Int get() {
            val c = Calendar.getInstance()
            c.timeInMillis = startMillis
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }
        val endMin: Int get() {
            val c = Calendar.getInstance()
            c.timeInMillis = endMillis
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }
        val startDayEpoch: Long get() = startMillis / 86_400_000L
        // All-day endMillis is midnight of the day AFTER — subtract 1ms to get inclusive last day
        val endDayEpoch: Long get() =
            if (isAllDay) (endMillis - 1) / 86_400_000L
            else endMillis / 86_400_000L
    }

    data class Attendee(
        val eventId: Long,
        val name: String,
        val email: String,
        val status: Int   // CalendarContract.Attendees.ATTENDEE_STATUS_*
    ) {
        /** Display name: prefer name, fall back to email. */
        val displayName: String get() = name.ifBlank { email }

        val statusLabel: String get() = when (status) {
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED  -> "✓"
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED  -> "✗"
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "?"
            else                                                  -> "·"
        }
    }

    // ── Calendars ─────────────────────────────────────────────────────────────

    fun getCalendars(): List<CalInfo> {
        val list = mutableListOf<CalInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE
        )
        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, null, null, null
            )
        } catch (e: Exception) { null }

        cursor?.use {
            while (it.moveToNext()) {
                list.add(CalInfo(
                    id           = it.getLong(0),
                    name         = it.getString(1) ?: "",
                    accountName  = it.getString(2) ?: "",
                    accountType  = it.getString(3) ?: "",
                    ownerAccount = it.getString(4) ?: "",
                    color        = it.getInt(5),
                    visible      = it.getInt(6) == 1
                ))
            }
        }
        return list
    }

    private fun calendarInfoForId(calendarId: Long): CalInfo? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE
        )
        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calendarId.toString()),
                null
            )
        } catch (e: Exception) { null }

        return cursor?.use {
            if (!it.moveToFirst()) return@use null
            CalInfo(
                id = it.getLong(0),
                name = it.getString(1) ?: "",
                accountName = it.getString(2) ?: "",
                accountType = it.getString(3) ?: "",
                ownerAccount = it.getString(4) ?: "",
                color = it.getInt(5),
                visible = it.getInt(6) == 1
            )
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private data class MasterEventInfo(
        val rrule: String?,
        val dtStart: Long?,
        val dtEnd: Long?,
        val duration: String?,
        val reminderMinutes: Int?
    )

    private fun loadMasterEventInfo(eventIds: Set<Long>): Map<Long, MasterEventInfo> {
        if (eventIds.isEmpty()) return emptyMap()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION
        )
        val idList = eventIds.joinToString(",")
        val selection = "${CalendarContract.Events._ID} IN ($idList)"

        val out = mutableMapOf<Long, MasterEventInfo>()
        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )
        } catch (e: Exception) { null }

        cursor?.use {
            val idxId = it.getColumnIndex(CalendarContract.Events._ID)
            val idxRRule = it.getColumnIndex(CalendarContract.Events.RRULE)
            val idxDtStart = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val idxDtEnd = it.getColumnIndex(CalendarContract.Events.DTEND)
            val idxDuration = it.getColumnIndex(CalendarContract.Events.DURATION)

            while (it.moveToNext()) {
                val id = it.getLong(idxId)
                val rrule = it.getString(idxRRule)
                val dtStart = if (idxDtStart >= 0 && !it.isNull(idxDtStart)) it.getLong(idxDtStart) else null
                val dtEnd = if (idxDtEnd >= 0 && !it.isNull(idxDtEnd)) it.getLong(idxDtEnd) else null
                val duration = if (idxDuration >= 0 && !it.isNull(idxDuration)) it.getString(idxDuration) else null
                out[id] = MasterEventInfo(
                    rrule = rrule?.takeIf { s -> s.isNotBlank() },
                    dtStart = dtStart,
                    dtEnd = dtEnd,
                    duration = duration?.takeIf { s -> s.isNotBlank() },
                    reminderMinutes = null
                )
            }
        }

        val reminderProjection = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES
        )
        val reminderSelection = "${CalendarContract.Reminders.EVENT_ID} IN ($idList)"
        val reminderCursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                reminderProjection,
                reminderSelection,
                null,
                "${CalendarContract.Reminders.MINUTES} ASC"
            )
        } catch (e: Exception) { null }

        reminderCursor?.use {
            val idxEventId = it.getColumnIndex(CalendarContract.Reminders.EVENT_ID)
            val idxMinutes = it.getColumnIndex(CalendarContract.Reminders.MINUTES)
            while (it.moveToNext()) {
                val eventId = it.getLong(idxEventId)
                val minutes = if (idxMinutes >= 0 && !it.isNull(idxMinutes)) it.getInt(idxMinutes) else continue
                val existing = out[eventId]
                if (existing != null && existing.reminderMinutes == null) {
                    out[eventId] = existing.copy(reminderMinutes = minutes)
                }
            }
        }

        return out
    }


    /**
     * Load all events in [startMillis, endMillis) from visible (enabled) calendars.
     * Pass enabledCalendarIds = null to read all calendars.
     */
    fun getEvents(
        startMillis: Long,
        endMillis: Long,
        enabledCalendarIds: Set<Long>? = null
    ): List<Event> {
        data class InstanceRow(
            val eventId: Long,
            val calendarId: Long,
            val title: String,
            val isAllDay: Boolean,
            val begin: Long,
            val end: Long,
            val location: String,
            val description: String
        )

        val rows = mutableListOf<InstanceRow>()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION
        )

        val calFilter = enabledCalendarIds?.joinToString(",") { it.toString() }
        val selection = if (calFilter != null)
            "${CalendarContract.Instances.CALENDAR_ID} IN ($calFilter)"
        else null

        val cursor: Cursor? = try {
            context.contentResolver.query(
                builder.build(), projection, selection, null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )
        } catch (e: Exception) { null }

        cursor?.use {
            val idxId       = it.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val idxCal      = it.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
            val idxTitle    = it.getColumnIndex(CalendarContract.Instances.TITLE)
            val idxAllDay   = it.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val idxBegin    = it.getColumnIndex(CalendarContract.Instances.BEGIN)
            val idxEnd      = it.getColumnIndex(CalendarContract.Instances.END)
            val idxLoc      = it.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            val idxDesc     = it.getColumnIndex(CalendarContract.Instances.DESCRIPTION)

            while (it.moveToNext()) {
                rows.add(
                    InstanceRow(
                        eventId = it.getLong(idxId),
                        calendarId = it.getLong(idxCal),
                        title = it.getString(idxTitle) ?: "(no title)",
                        isAllDay = it.getInt(idxAllDay) == 1,
                        begin = it.getLong(idxBegin),
                        end = it.getLong(idxEnd),
                        location = it.getString(idxLoc) ?: "",
                        description = it.getString(idxDesc) ?: ""
                    )
                )
            }
        }

        val masterInfo = loadMasterEventInfo(rows.map { it.eventId }.toSet())

        return rows.map { row ->
            val master = masterInfo[row.eventId]
            Event(
                id = row.eventId,
                calendarId = row.calendarId,
                title = row.title,
                isAllDay = row.isAllDay,
                startMillis = row.begin,
                endMillis = row.end,
                location = row.location,
                description = row.description,
                rrule = master?.rrule,
                reminderMinutes = master?.reminderMinutes,
                originalStartMillis = master?.dtStart,
                originalEndMillis = master?.dtEnd
            )
        }
    }

    // ── Attendees ─────────────────────────────────────────────────────────────

    /**
     * Returns all attendees for a given event ID.
     * The organiser (self) is usually included by Google's sync adapter.
     */
    fun getAttendees(eventId: Long): List<Attendee> {
        val list = mutableListOf<Attendee>()
        val projection = arrayOf(
            CalendarContract.Attendees.EVENT_ID,
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_STATUS
        )
        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                projection,
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString()),
                null
            )
        } catch (e: Exception) { null }

        cursor?.use {
            val idxId     = it.getColumnIndex(CalendarContract.Attendees.EVENT_ID)
            val idxName   = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_NAME)
            val idxEmail  = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_EMAIL)
            val idxStatus = it.getColumnIndex(CalendarContract.Attendees.ATTENDEE_STATUS)
            while (it.moveToNext()) {
                list.add(Attendee(
                    eventId = it.getLong(idxId),
                    name    = it.getString(idxName)  ?: "",
                    email   = it.getString(idxEmail) ?: "",
                    status  = if (idxStatus >= 0) it.getInt(idxStatus)
                    else CalendarContract.Attendees.ATTENDEE_STATUS_NONE
                ))
            }
        }
        return list
    }

    /**
     * Adds a list of email addresses as required attendees on an existing event.
     * Returns the number of attendees successfully inserted.
     * On Google Calendar accounts the sync adapter will dispatch the invitations.
     */
    fun addAttendees(eventId: Long, emails: List<String>): Int {
        var count = 0
        for (email in emails.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            val values = ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, eventId)
                put(CalendarContract.Attendees.ATTENDEE_NAME, email)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(
                    CalendarContract.Attendees.ATTENDEE_TYPE,
                    CalendarContract.Attendees.TYPE_REQUIRED
                )
                put(
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                )
            }
            try {
                if (context.contentResolver.insert(
                        CalendarContract.Attendees.CONTENT_URI, values) != null) count++
            } catch (_: Exception) { }
        }
        return count
    }

    /**
     * Replaces all attendees on an event with [emails].
     * Pass an empty list to remove all attendees.
     */
    fun replaceAttendees(eventId: Long, emails: List<String>) {
        try {
            context.contentResolver.delete(
                CalendarContract.Attendees.CONTENT_URI,
                "${CalendarContract.Attendees.EVENT_ID} = ?",
                arrayOf(eventId.toString())
            )
        } catch (_: Exception) { }
        if (emails.isNotEmpty()) addAttendees(eventId, emails)
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Returns the OWNER_ACCOUNT (organizer e-mail) for the given calendar, or null. */
    private fun ownerAccountForCalendar(calendarId: Long): String? {
        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.OWNER_ACCOUNT),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calendarId.toString()),
                null
            )
        } catch (e: Exception) { null }
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { s -> s.isNotBlank() }
            else null
        }
    }

    private fun applyCalendarIdentity(
        values: ContentValues,
        calendarId: Long,
        attendeeEmails: List<String>
    ) {
        if (attendeeEmails.isNotEmpty()) {
            values.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        }
    }

    private fun isGoogleCalendar(cal: CalInfo?): Boolean {
        if (cal == null) return false
        val accountType = cal.accountType.trim().lowercase()
        return accountType == "com.google" || accountType.contains("google")
    }

    private fun eventInsertUriForCalendar(calendarId: Long): Uri {
        val cal = calendarInfoForId(calendarId)
        if (!isGoogleCalendar(cal)) return CalendarContract.Events.CONTENT_URI

        return CalendarContract.Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, cal!!.accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, cal.accountType)
            .build()
    }

    private fun eventUpdateUriForCalendar(eventId: Long, calendarId: Long): Uri {
        val baseUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cal = calendarInfoForId(calendarId)
        if (!isGoogleCalendar(cal)) return baseUri

        return baseUri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, cal!!.accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, cal.accountType)
            .build()
    }

    private fun replaceReminders(eventId: Long, reminderMinutes: Int?) {
        val reminderUri = CalendarContract.Reminders.CONTENT_URI
        try {
            context.contentResolver.delete(
                reminderUri,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString())
            )
        } catch (_: Exception) {
        }

        if (reminderMinutes == null) return

        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, reminderMinutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        try {
            context.contentResolver.insert(reminderUri, values)
        } catch (_: Exception) {
        }
    }

    /**
     * Insert a timed event. Returns the new event URI string, or null on failure.
     */
    fun insertTimedEvent(
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = "",
        location: String = "",
        rrule: String? = null,
        reminderMinutes: Int? = null,
        attendeeEmails: List<String> = emptyList()
    ): String? {
        val cleanAttendees = attendeeEmails.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!rrule.isNullOrBlank()) put(CalendarContract.Events.RRULE, rrule)
            applyCalendarIdentity(this, calendarId, cleanAttendees)
        }
        return try {
            val uri = context.contentResolver.insert(eventInsertUriForCalendar(calendarId), values)
            val eventId = uri?.let { ContentUris.parseId(it) }
            if (eventId != null) {
                replaceReminders(eventId, reminderMinutes)
                if (cleanAttendees.isNotEmpty()) addAttendees(eventId, cleanAttendees)
            }
            uri?.toString()
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "insertTimedEvent failed", e)
            null
        }
    }

    /**
     * Insert an all-day event. endDate is the EXCLUSIVE end date (day after last day).
     */
    fun insertAllDayEvent(
        calendarId: Long,
        title: String,
        startDayMillis: Long,   // midnight UTC of start day
        endDayMillis: Long,     // midnight UTC of day AFTER end (exclusive)
        description: String = "",
        location: String = "",
        rrule: String? = null,
        reminderMinutes: Int? = null,
        attendeeEmails: List<String> = emptyList()
    ): String? {
        val cleanAttendees = attendeeEmails.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startDayMillis)
            put(CalendarContract.Events.DTEND, endDayMillis)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            if (!rrule.isNullOrBlank()) put(CalendarContract.Events.RRULE, rrule)
            applyCalendarIdentity(this, calendarId, cleanAttendees)
        }
        return try {
            val uri = context.contentResolver.insert(eventInsertUriForCalendar(calendarId), values)
            val eventId = uri?.let { ContentUris.parseId(it) }
            if (eventId != null) {
                replaceReminders(eventId, reminderMinutes)
                if (cleanAttendees.isNotEmpty()) addAttendees(eventId, cleanAttendees)
            }
            uri?.toString()
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "insertAllDayEvent failed", e)
            null
        }
    }

    private fun existingMasterInfo(eventId: Long): MasterEventInfo? =
        loadMasterEventInfo(setOf(eventId))[eventId]

    private fun combineDateWithTime(baseMillis: Long, timeMillis: Long, timezoneId: String = TimeZone.getDefault().id): Long {
        val base = Calendar.getInstance(TimeZone.getTimeZone(timezoneId)).apply { timeInMillis = baseMillis }
        val time = Calendar.getInstance(TimeZone.getTimeZone(timezoneId)).apply { timeInMillis = timeMillis }
        base.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY))
        base.set(Calendar.MINUTE, time.get(Calendar.MINUTE))
        base.set(Calendar.SECOND, 0)
        base.set(Calendar.MILLISECOND, 0)
        return base.timeInMillis
    }

    private fun combineUtcDate(baseMillisUtc: Long, dateMillisUtc: Long): Long {
        val tz = TimeZone.getTimeZone("UTC")
        val base = Calendar.getInstance(tz).apply { timeInMillis = baseMillisUtc }
        val date = Calendar.getInstance(tz).apply { timeInMillis = dateMillisUtc }
        base.set(Calendar.YEAR, date.get(Calendar.YEAR))
        base.set(Calendar.MONTH, date.get(Calendar.MONTH))
        base.set(Calendar.DAY_OF_MONTH, date.get(Calendar.DAY_OF_MONTH))
        base.set(Calendar.HOUR_OF_DAY, 0)
        base.set(Calendar.MINUTE, 0)
        base.set(Calendar.SECOND, 0)
        base.set(Calendar.MILLISECOND, 0)
        return base.timeInMillis
    }

    fun updateTimedEvent(
        eventId: Long,
        calendarId: Long,
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String = "",
        location: String = "",
        rrule: String? = null,
        reminderMinutes: Int? = null,
        attendeeEmails: List<String>? = null   // null = leave unchanged, empty = remove all
    ): Boolean {
        val existing = existingMasterInfo(eventId)
        val hasExistingSeries = !existing?.rrule.isNullOrBlank()
        val hasNewSeries = !rrule.isNullOrBlank()

        val finalStartMillis =
            if (hasExistingSeries && hasNewSeries && existing?.dtStart != null) {
                combineDateWithTime(existing.dtStart, startMillis)
            } else {
                startMillis
            }

        val duration = (endMillis - startMillis).coerceAtLeast(60_000L)
        val finalEndMillis =
            if (hasExistingSeries && hasNewSeries) {
                finalStartMillis + duration
            } else {
                endMillis
            }

        val cleanAttendees = attendeeEmails?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, finalStartMillis)
            put(CalendarContract.Events.DTEND, finalEndMillis)
            putNull(CalendarContract.Events.DURATION)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.RRULE, rrule ?: "")
            applyCalendarIdentity(this, calendarId, cleanAttendees.orEmpty())
        }
        return try {
            val uri = eventUpdateUriForCalendar(eventId, calendarId)
            val updated = context.contentResolver.update(uri, values, null, null) > 0
            if (updated) {
                replaceReminders(eventId, reminderMinutes)
                if (cleanAttendees != null) replaceAttendees(eventId, cleanAttendees)
            }
            updated
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "updateTimedEvent failed", e)
            false
        }
    }

    fun updateAllDayEvent(
        eventId: Long,
        calendarId: Long,
        title: String,
        startDayMillis: Long,
        endDayMillis: Long,
        description: String = "",
        location: String = "",
        rrule: String? = null,
        reminderMinutes: Int? = null,
        attendeeEmails: List<String>? = null   // null = leave unchanged, empty = remove all
    ): Boolean {
        val existing = existingMasterInfo(eventId)
        val hasExistingSeries = !existing?.rrule.isNullOrBlank()
        val hasNewSeries = !rrule.isNullOrBlank()

        val finalStartDayMillis =
            if (hasExistingSeries && hasNewSeries && existing?.dtStart != null) {
                combineUtcDate(existing.dtStart, startDayMillis)
            } else {
                startDayMillis
            }

        val duration = (endDayMillis - startDayMillis).coerceAtLeast(86_400_000L)
        val finalEndDayMillis =
            if (hasExistingSeries && hasNewSeries) {
                finalStartDayMillis + duration
            } else {
                endDayMillis
            }

        val cleanAttendees = attendeeEmails?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, finalStartDayMillis)
            put(CalendarContract.Events.DTEND, finalEndDayMillis)
            putNull(CalendarContract.Events.DURATION)
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.RRULE, rrule ?: "")
            applyCalendarIdentity(this, calendarId, cleanAttendees.orEmpty())
        }
        return try {
            val uri = eventUpdateUriForCalendar(eventId, calendarId)
            val updated = context.contentResolver.update(uri, values, null, null) > 0
            if (updated) {
                replaceReminders(eventId, reminderMinutes)
                if (cleanAttendees != null) replaceAttendees(eventId, cleanAttendees)
            }
            updated
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "updateAllDayEvent failed", e)
            false
        }
    }

    fun parseEventId(uriString: String?): Long? {
        if (uriString.isNullOrBlank()) return null
        return try {
            ContentUris.parseId(Uri.parse(uriString))
        } catch (e: Exception) {
            null
        }
    }


    private data class RecurringDeleteInfo(
        val calendarId: Long,
        val isAllDay: Boolean,
        val rrule: String?,
        val dtStart: Long?,
        val eventTimezone: String?
    )

    private fun loadRecurringDeleteInfo(eventId: Long): RecurringDeleteInfo? {
        val projection = arrayOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_TIMEZONE
        )
        val cursor: Cursor? = try {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                projection,
                null,
                null,
                null
            )
        } catch (e: Exception) { null }

        return cursor?.use {
            if (!it.moveToFirst()) return@use null
            RecurringDeleteInfo(
                calendarId = it.getLong(0),
                isAllDay = it.getInt(1) == 1,
                rrule = it.getString(2),
                dtStart = if (!it.isNull(3)) it.getLong(3) else null,
                eventTimezone = it.getString(4)
            )
        }
    }

    private fun formatUntilForRecurringDelete(instanceStartMillis: Long, isAllDay: Boolean): String {
        return if (isAllDay) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = instanceStartMillis - 86_400_000L
            }
            SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(cal.time)
        } else {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = instanceStartMillis - 1000L
            }
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(cal.time)
        }
    }

    private fun replaceUntilInRRule(rrule: String, untilValue: String): String {
        val parts = rrule.split(';')
            .filter { it.isNotBlank() }
            .filterNot {
                val u = it.uppercase(Locale.US)
                u.startsWith("UNTIL=") || u.startsWith("COUNT=")
            }
            .toMutableList()
        parts.add("UNTIL=$untilValue")
        return parts.joinToString(";")
    }

    fun deleteSingleOccurrence(event: Event): Boolean {
        val info = loadRecurringDeleteInfo(event.id) ?: return deleteEvent(event.id)
        if (info.rrule.isNullOrBlank()) return deleteEvent(event.id)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, info.calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (info.isAllDay) "UTC" else (info.eventTimezone ?: TimeZone.getDefault().id))
            put(CalendarContract.Events.ALL_DAY, if (info.isAllDay) 1 else 0)
            put(CalendarContract.Events.ORIGINAL_ID, event.id)
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, event.startMillis)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
        }
        return try {
            context.contentResolver.insert(eventInsertUriForCalendar(info.calendarId), values) != null
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "deleteSingleOccurrence failed", e)
            false
        }
    }

    fun deleteThisAndFuture(event: Event): Boolean {
        val info = loadRecurringDeleteInfo(event.id) ?: return deleteEvent(event.id)
        val rrule = info.rrule ?: return deleteEvent(event.id)
        val masterStart = info.dtStart ?: return deleteEvent(event.id)

        if (event.startMillis <= masterStart + 1000L) {
            return deleteEvent(event.id)
        }

        val untilValue = formatUntilForRecurringDelete(event.startMillis, info.isAllDay)
        val trimmedRRule = replaceUntilInRRule(rrule, untilValue)
        val values = ContentValues().apply {
            put(CalendarContract.Events.RRULE, trimmedRRule)
        }
        return try {
            val uri = eventUpdateUriForCalendar(event.id, info.calendarId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "deleteThisAndFuture failed", e)
            false
        }
    }

    /**
     * Delete an event by ID. Returns true if an event row was removed.
     */
    fun deleteEvent(eventId: Long): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            android.util.Log.e("MiniCal", "deleteEvent failed", e)
            false
        }
    }

}
