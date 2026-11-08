package com.gustav.minicalendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.app.TimePickerDialog
import android.app.DatePickerDialog
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import android.widget.Button
import android.app.AlarmManager
import android.content.Context

sealed class WeekSlot {
    data class Active(val hour: Int) : WeekSlot()
    data class Gap(val from: Int, val to: Int) : WeekSlot()
}

class MainActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var body: TextView
    private lateinit var bodyContainer: android.widget.FrameLayout
    private var monthCanvas: MonthCanvasView? = null
    private var weekCanvas: WeekCanvasView? = null
    private var dayCanvas: DayListCanvasView? = null
    private var settingsScrollView: ScrollView? = null
    private var settingsContent: LinearLayout? = null

    private var bodyCols: Int = 42
    private var writeCalendarId: Long? = null
    private var showDebug: Boolean = false
    private var showFooter: Boolean = true
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            if (repo != null) {
                reloadEvents()
                AlarmScheduler.scheduleNext(this@MainActivity)
                render()
            }
            refreshHandler.postDelayed(this, 60_000L)
        }
    }
    private val calendarObserverHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var calendarObserverRegistered = false
    private var calendarObserverReschedulePending = false
    private val calendarObserver = object : android.database.ContentObserver(calendarObserverHandler) {
        override fun onChange(selfChange: Boolean) {
            handleCalendarProviderChanged()
        }

        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            handleCalendarProviderChanged()
        }
    }
    private var screen: Screen = Screen.MONTH
    private var previousScreen: Screen = Screen.MONTH
    private val screenHistory = ArrayDeque<Screen>()
    private var lastRenderedScreenForDarkRefresh: Screen? = null
    private var epaperRefreshOverlay: View? = null
    private var selectedEvent: CalendarRepository.Event? = null
    private var eventDeletePending: Boolean = false
    private var recurringDeleteMenuPending: Boolean = false
    private var lastKey: String = "ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â"
    private var selectedSlot: Int? = null
    private var numberBuffer: String = ""
    private var selectedDay: Calendar = Calendar.getInstance()
    private var pageOffset: Int = 0   // paging for week/day overflow (p/o keys)
    private var settingsPageOffset: Int = 0   // paging within settings view (o/p keys)

    private lateinit var captureInput: EditText
    private var activeInvitees: MutableList<String>? = null
    private var activeInviteesRenderer: (() -> Unit)? = null
    private var activeInviteesManualField: EditText? = null
    private val pickInviteeEmailLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uri = data.data ?: return@registerForActivityResult
            try {
                contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        val email = cursor.getString(idx)?.trim().orEmpty()
                        if (email.isNotBlank()) {
                            appendInviteeEmail(email)
                        }
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.toast_could_not_read_selected_email), Toast.LENGTH_SHORT).show()
            }
        }

    // Capture state
    private var captureMode: Screen = Screen.WEEK  // screen to return to after capture
    private var captureIsAllDay: Boolean = false

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dow1Fmt get() = SimpleDateFormat("EEE", locale())

    // CalendarRepository ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â initialised after permission granted
    private var repo: CalendarRepository? = null
    private var enabledCalendarIds: MutableSet<Long> = mutableSetOf()

    // Cached events (reloaded on navigation)
    private var cachedWeekEvents: Map<Int, List<CalendarRepository.Event>> = emptyMap()
    private var cachedMonthEvents: List<CalendarRepository.Event> = emptyList()
    private var calendarMap: Map<Long, CalendarRepository.CalInfo> = emptyMap()

    private val PERM_REQUEST = 1001
    private val CONTACTS_PERM_REQUEST = 1002

    companion object {
        private const val EXTRA_OPEN_EVENT = "open_event"
        private const val EXTRA_OPEN_FOCUS_DAY = "com.gustav.minicalendar.extra.OPEN_FOCUS_DAY"
        private const val EXTRA_FOCUS_DAY_MILLIS = "com.gustav.minicalendar.extra.FOCUS_DAY_MILLIS"
        private const val EXTRA_OPEN_CAPTURE_DAY = "com.gustav.minicalendar.extra.OPEN_CAPTURE_DAY"
        private const val EXTRA_CAPTURE_ALL_DAY = "com.gustav.minicalendar.extra.CAPTURE_ALL_DAY"
    }

    private fun hasCalendarPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    private fun requestCalendarPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ),
            PERM_REQUEST
        )
    }

    private fun consumeOpenEventIntent(intent: Intent?) {
        val srcIntent = intent ?: return
        val shouldOpenEvent = srcIntent.getBooleanExtra(EXTRA_OPEN_EVENT, false)
        if (!shouldOpenEvent) return
        if (!hasCalendarPermissions()) return
        if (repo == null) initRepo()

        val eventId = srcIntent.getLongExtra(AlarmReceiver.EXTRA_EVENT_ID, -1L)
        val eventStart = srcIntent.getLongExtra(AlarmReceiver.EXTRA_EVENT_START, -1L)
        if (eventId <= 0L || eventStart <= 0L) {
            srcIntent.removeExtra(EXTRA_OPEN_EVENT)
            srcIntent.removeExtra(AlarmReceiver.EXTRA_EVENT_ID)
            srcIntent.removeExtra(AlarmReceiver.EXTRA_EVENT_START)
            return
        }

        openEventFromNotification(eventId, eventStart)

        srcIntent.removeExtra(EXTRA_OPEN_EVENT)
        srcIntent.removeExtra(AlarmReceiver.EXTRA_EVENT_ID)
        srcIntent.removeExtra(AlarmReceiver.EXTRA_EVENT_START)
    }

    private fun consumeOpenFocusDayIntent(intent: Intent?) {
        val srcIntent = intent ?: return
        val shouldOpenFocusDay = srcIntent.getBooleanExtra(EXTRA_OPEN_FOCUS_DAY, false)
        if (!shouldOpenFocusDay) return
        if (!hasCalendarPermissions()) return
        if (repo == null) initRepo()
        screenHistory.clear()

        val focusDayMillis = srcIntent.getLongExtra(EXTRA_FOCUS_DAY_MILLIS, -1L)
        if (focusDayMillis <= 0L) {
            srcIntent.removeExtra(EXTRA_OPEN_FOCUS_DAY)
            srcIntent.removeExtra(EXTRA_FOCUS_DAY_MILLIS)
            return
        }

        selectedDay = Calendar.getInstance().apply {
            timeInMillis = focusDayMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        selectedEvent = null
        clearDeleteState()
        clearSelection()
        reloadEvents()
        previousScreen = Screen.MONTH
        screen = Screen.FOCUS
        lastKey = "launcher -> focus"

        srcIntent.removeExtra(EXTRA_OPEN_FOCUS_DAY)
        srcIntent.removeExtra(EXTRA_FOCUS_DAY_MILLIS)
    }

    private fun consumeOpenCaptureDayIntent(intent: Intent?) {
        val srcIntent = intent ?: return
        val shouldOpenCaptureDay = srcIntent.getBooleanExtra(EXTRA_OPEN_CAPTURE_DAY, false)
        if (!shouldOpenCaptureDay) return
        if (!hasCalendarPermissions()) return
        if (repo == null) initRepo()
        screenHistory.clear()

        val focusDayMillis = srcIntent.getLongExtra(EXTRA_FOCUS_DAY_MILLIS, -1L)
        if (focusDayMillis <= 0L) {
            srcIntent.removeExtra(EXTRA_OPEN_CAPTURE_DAY)
            srcIntent.removeExtra(EXTRA_FOCUS_DAY_MILLIS)
            srcIntent.removeExtra(EXTRA_CAPTURE_ALL_DAY)
            return
        }

        val isAllDay = srcIntent.getBooleanExtra(EXTRA_CAPTURE_ALL_DAY, false)

        selectedDay = Calendar.getInstance().apply {
            timeInMillis = focusDayMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        selectedEvent = null
        clearDeleteState()
        clearSelection()
        reloadEvents()
        previousScreen = Screen.FOCUS
        screen = Screen.FOCUS
        lastKey = "launcher -> capture"

        srcIntent.removeExtra(EXTRA_OPEN_CAPTURE_DAY)
        srcIntent.removeExtra(EXTRA_FOCUS_DAY_MILLIS)
        srcIntent.removeExtra(EXTRA_CAPTURE_ALL_DAY)

        showCaptureDialog(isAllDay = isAllDay)
    }

    private fun openEventFromNotification(eventId: Long, eventStart: Long) {
        screenHistory.clear()
        val targetDay = Calendar.getInstance().apply {
            timeInMillis = eventStart
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        selectedDay = targetDay
        clearSelection()
        reloadEvents()

        val dayStart = targetDay.timeInMillis
        val dayEnd = dayStart + 86_400_000L

        val eventsForDay = repo?.getEvents(
            dayStart,
            dayEnd,
            enabledCalendarIds
        ).orEmpty()

        val matched = eventsForDay.firstOrNull { it.id == eventId && it.startMillis == eventStart }
            ?: eventsForDay.firstOrNull { it.id == eventId }
            ?: getFocusEventsForSelectedDay().firstOrNull { it.id == eventId && it.startMillis == eventStart }
            ?: getFocusEventsForSelectedDay().firstOrNull { it.id == eventId }

        if (matched != null) {
            selectedEvent = matched
            clearDeleteState()
            previousScreen = Screen.FOCUS
            screen = Screen.EVENT
            lastKey = "notification -> event"
        } else {
            selectedEvent = null
            previousScreen = Screen.MONTH
            screen = Screen.FOCUS
            lastKey = "notification -> day"
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        header = findViewById(R.id.header)
        body = findViewById(R.id.body)
        bodyContainer = findViewById(R.id.bodyContainer)
        captureInput = findViewById(R.id.captureInput)

        body.isFocusableInTouchMode = true
        body.requestFocus()

        if (hasCalendarPermissions()) {
            initRepo()
            ensureNotificationPermission()
            consumeOpenFocusDayIntent(intent)
            consumeOpenCaptureDayIntent(intent)
            consumeOpenEventIntent(intent)
        } else {
            requestCalendarPermissions()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation(fromSystem = true)
            }
        })

        render()

        body.post {
            bodyCols = estimateMonospaceColumns(body)
            render()
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenFocusDayIntent(intent)
        consumeOpenCaptureDayIntent(intent)
        consumeOpenEventIntent(intent)
        render()
    }

    private fun registerCalendarObserver() {
        if (calendarObserverRegistered) return
        try {
            contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver)
            contentResolver.registerContentObserver(CalendarContract.Reminders.CONTENT_URI, true, calendarObserver)
            contentResolver.registerContentObserver(CalendarContract.Calendars.CONTENT_URI, true, calendarObserver)
            calendarObserverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("MiniCalAlarm", "Could not register calendar observer", e)
        }
    }

    private fun unregisterCalendarObserver() {
        if (!calendarObserverRegistered) return
        try {
            contentResolver.unregisterContentObserver(calendarObserver)
        } catch (_: Exception) {
        }
        calendarObserverRegistered = false
    }

    private fun handleCalendarProviderChanged() {
        if (calendarObserverReschedulePending) return
        calendarObserverReschedulePending = true
        calendarObserverHandler.postDelayed({
            calendarObserverReschedulePending = false
            if (!hasCalendarPermissions()) return@postDelayed
            if (repo == null) initRepo() else reloadEvents()
            AlarmScheduler.scheduleNext(this)
            render()
        }, 1_000L)
    }

    override fun onResume() {
        super.onResume()
        if (hasCalendarPermissions()) {
            if (repo == null) initRepo()
            ensureNotificationPermission()
            registerCalendarObserver()
            if (repo != null) {
                reloadEvents()
                AlarmScheduler.scheduleNext(this)
                render()
            }
        } else {
            unregisterCalendarObserver()
            repo = null
            enabledCalendarIds.clear()
            cachedWeekEvents = emptyMap()
            cachedMonthEvents = emptyList()
            calendarMap = emptyMap()
            render()
        }
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 60_000L)
    }

    override fun onPause() {
        super.onPause()
        unregisterCalendarObserver()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERM_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                initRepo()
                ensureNotificationPermission()
                registerCalendarObserver()
                AlarmScheduler.scheduleNext(this)
                consumeOpenFocusDayIntent(intent)
                consumeOpenEventIntent(intent)
            } else {
                unregisterCalendarObserver()
                repo = null
                enabledCalendarIds.clear()
                cachedWeekEvents = emptyMap()
                cachedMonthEvents = emptyList()
                calendarMap = emptyMap()
                Toast.makeText(this, getString(R.string.toast_calendar_permission_denied), Toast.LENGTH_SHORT).show()
            }
            render()
            return
        }

        if (requestCode == NOTIFICATION_PERM_REQUEST) {
            render()
            return
        }

        if (requestCode == CONTACTS_PERM_REQUEST) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                launchInviteeEmailPicker()
            } else {
                Toast.makeText(this, getString(R.string.toast_contacts_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("minicalendar", MODE_PRIVATE) }
    private val uiRegularTypeface by lazy {
        ResourcesCompat.getFont(this, R.font.work_sans_regular) ?: Typeface.SANS_SERIF
    }
    private val uiMediumTypeface by lazy {
        ResourcesCompat.getFont(this, R.font.work_sans_medium) ?: uiRegularTypeface
    }
    private val weekStartPreferenceOptions = listOf(
        0,
        Calendar.MONDAY,
        Calendar.SUNDAY
    )

    private val alarmFallbackOptions = listOf(0, 5, 10, 15, 30, 60)

    private fun localeDefaultFirstDayOfWeek(): Int =
        Calendar.getInstance(locale()).firstDayOfWeek

    private fun localeMinimalDaysInFirstWeek(): Int =
        Calendar.getInstance(locale()).minimalDaysInFirstWeek

    private fun configuredWeekStartPreference(): Int =
        prefs.getInt("week_start_day", 0)

    private fun setWeekStartPreference(dayOfWeek: Int) {
        val normalized = if (dayOfWeek in weekStartPreferenceOptions) dayOfWeek else 0
        prefs.edit().putInt("week_start_day", normalized).apply()
    }

    private fun darkModeEnabled(): Boolean =
        prefs.getBoolean("dark_mode_enabled", false)

    private fun setDarkModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode_enabled", enabled).apply()
    }

    private fun darkModeEpaperRefreshEnabled(): Boolean =
        prefs.getBoolean("dark_mode_epaper_refresh_enabled", true)

    private fun setDarkModeEpaperRefreshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode_epaper_refresh_enabled", enabled).apply()
    }

    private fun appBgColor(): Int = if (darkModeEnabled()) Color.BLACK else Color.WHITE
    private fun appFgColor(): Int = if (darkModeEnabled()) Color.WHITE else Color.BLACK
    private fun appSubtleTextColor(): Int = if (darkModeEnabled()) Color.rgb(190, 190, 190) else Color.DKGRAY
    private fun appPanelColor(): Int = if (darkModeEnabled()) Color.rgb(24, 24, 24) else Color.WHITE
    private fun appStrokeColor(): Int = if (darkModeEnabled()) Color.rgb(95, 95, 95) else Color.BLACK

    private fun applyAppThemeChrome() {
        val bg = appBgColor()
        val fg = appFgColor()
        findViewById<View>(R.id.root)?.setBackgroundColor(bg)
        header.setBackgroundColor(bg)
        header.setTextColor(fg)
        bodyContainer.setBackgroundColor(bg)
        body.setBackgroundColor(bg)
        body.setTextColor(fg)
        captureInput.setBackgroundColor(bg)
        captureInput.setTextColor(fg)
        captureInput.setHintTextColor(appSubtleTextColor())
    }

    private fun maybeFlashEpaperOnDarkViewChange() {
        val previous = lastRenderedScreenForDarkRefresh
        val current = screen
        lastRenderedScreenForDarkRefresh = current
        if (!darkModeEnabled() || !darkModeEpaperRefreshEnabled() || previous == null || previous == current) return
        flashEpaperRefresh()
    }

    private fun flashEpaperRefresh() {
        val root = window.decorView as? ViewGroup ?: return
        epaperRefreshOverlay?.let { old ->
            runCatching { root.removeView(old) }
        }

        val overlay = View(this).apply {
            setBackgroundColor(Color.WHITE)
            isClickable = false
        }
        epaperRefreshOverlay = overlay
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.postDelayed({
            if (epaperRefreshOverlay !== overlay) return@postDelayed
            overlay.setBackgroundColor(Color.BLACK)
            overlay.invalidate()
            overlay.postDelayed({
                if (epaperRefreshOverlay !== overlay) return@postDelayed
                runCatching { root.removeView(overlay) }
                epaperRefreshOverlay = null
                root.invalidate()
                bodyContainer.invalidate()
                header.invalidate()
            }, 120L)
        }, 120L)
    }

    private fun themedSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                styleSpinnerView(super.getView(position, convertView, parent), dropdown = false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                styleSpinnerView(super.getDropDownView(position, convertView, parent), dropdown = true)
        }

    private fun styleSpinnerView(view: View, dropdown: Boolean): View {
        view.setBackgroundColor(if (dropdown) appPanelColor() else appBgColor())
        (view as? TextView)?.apply {
            setTextColor(appFgColor())
            setHintTextColor(appSubtleTextColor())
            typeface = uiRegularTypeface
            textSize = 12f
        }
        return view
    }

    private fun resolvedFirstDayOfWeek(): Int {
        val saved = configuredWeekStartPreference()
        return if (saved in Calendar.SUNDAY..Calendar.SATURDAY) saved else localeDefaultFirstDayOfWeek()
    }

    private fun cycleWeekStartPreference() {
        val current = configuredWeekStartPreference()
        val idx = weekStartPreferenceOptions.indexOf(current).let { if (it >= 0) it else 0 }
        val next = weekStartPreferenceOptions[(idx + 1) % weekStartPreferenceOptions.size]
        setWeekStartPreference(next)
    }

    private fun localizedWeekdayName(dayOfWeek: Int): String {
        val names = DateFormatSymbols.getInstance(locale()).weekdays
        return names.getOrNull(dayOfWeek)?.takeIf { it.isNotBlank() }
            ?: Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dayOfWeek) }.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale()).orEmpty()
    }

    private fun weekStartLabel(): String {
        val current = configuredWeekStartPreference()
        val defaultDay = localizedWeekdayName(localeDefaultFirstDayOfWeek())
        return if (current == 0) {
            getString(R.string.label_week_start_system_default, defaultDay)
        } else {
            localizedWeekdayName(current)
        }
    }

    private fun configureWeekCalendar(calendar: Calendar, firstDayOfWeek: Int = resolvedFirstDayOfWeek()): Calendar =
        calendar.apply {
            this.firstDayOfWeek = firstDayOfWeek
            minimalDaysInFirstWeek = localeMinimalDaysInFirstWeek()
        }

    private fun weekNumberFor(calendar: Calendar, firstDayOfWeek: Int = resolvedFirstDayOfWeek()): Int =
        configureWeekCalendar(calendar.clone() as Calendar, firstDayOfWeek).get(Calendar.WEEK_OF_YEAR)

    private fun alarmEngineEnabled(): Boolean =
        prefs.getBoolean("alarm_engine_enabled", false)

    private fun setAlarmEngineEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("alarm_engine_enabled", enabled).apply()
    }

    private fun toggleAlarmEngine() {
        setAlarmEngineEnabled(!alarmEngineEnabled())
    }

    private fun setWriteCalendar(calendarId: Long?) {
        writeCalendarId = calendarId
        val editor = prefs.edit()
        if (calendarId != null) {
            editor.putLong("write_calendar_id", calendarId)
        } else {
            editor.remove("write_calendar_id")
        }
        editor.apply()
    }

    private fun alarmFallbackMinutes(): Int {
        val saved = prefs.getInt("alarm_engine_fallback_minutes", 10)
        return if (saved in alarmFallbackOptions) saved else 10
    }

    private fun cycleAlarmFallbackMinutes() {
        val current = alarmFallbackMinutes()
        val idx = alarmFallbackOptions.indexOf(current).let { if (it >= 0) it else 0 }
        val next = alarmFallbackOptions[(idx + 1) % alarmFallbackOptions.size]
        prefs.edit().putInt("alarm_engine_fallback_minutes", next).apply()
    }

    private fun setAlarmFallbackMinutes(minutes: Int) {
        val normalized = if (minutes in alarmFallbackOptions) minutes else alarmFallbackOptions.first()
        prefs.edit().putInt("alarm_engine_fallback_minutes", normalized).apply()
    }

    private fun alarmFallbackLabel(): String =
        if (alarmFallbackMinutes() == 0) getString(R.string.label_at_time_of_event)
        else getString(R.string.label_minutes_before, alarmFallbackMinutes())

    private fun alarmFallbackOptionLabel(minutes: Int): String =
        if (minutes == 0) getString(R.string.label_at_time_of_event)
        else getString(R.string.label_minutes_before, minutes)


    private fun initRepo() {
        repo = CalendarRepository(this)
        // Default: enable all calendars
        val cals = repo!!.getCalendars()
        val savedReadIds = CalendarSelection.enabledIds(prefs)

        enabledCalendarIds = if (savedReadIds != null) {
            cals.map { it.id }.filter { it in savedReadIds }.toMutableSet()
        } else {
            cals.map { it.id }.toMutableSet().also { ids ->
                ids.forEach { id -> prefs.edit().putBoolean("read_calendar_$id", true).apply() }
            }
        }
        calendarMap = cals.associateBy { it.id }
        // Restore last-used write calendar, fall back to first Google account calendar
        val savedId = prefs.getLong("write_calendar_id", -1L)
        writeCalendarId = if (savedId != -1L && cals.any { it.id == savedId }) {
            savedId
        } else {
            cals.firstOrNull {
                it.accountName.contains("@") || it.accountName.contains("google", ignoreCase = true)
            }?.id ?: cals.firstOrNull()?.id
        }
        reloadEvents()
    }

    private fun reloadEvents() {
        val r = repo ?: return
        val enabled = enabledCalendarIds
        calendarMap = r.getCalendars().associateBy { it.id }

        // Week window
        val days = weekDays()
        val weekStart = startOfWeek(selectedDay)
        val weekEnd   = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
        val weekEvs   = r.getEvents(weekStart.timeInMillis, weekEnd.timeInMillis, enabled)

        val map = mutableMapOf<Int, MutableList<CalendarRepository.Event>>()
        for ((idx, day) in days.withIndex()) {
            // Local midnight range for timed events
            val midnight = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val localDayStartMs = midnight.timeInMillis
            val localDayEndMs   = localDayStartMs + 86_400_000L
            for (ev in weekEvs) {
                val overlaps = if (ev.isAllDay) {
                    // Compare UTC day epochs directly ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â all-day events are stored as UTC midnight
                    val evStartEpoch = ev.startMillis / 86_400_000L
                    val evEndEpoch   = (ev.endMillis - 1) / 86_400_000L
                    // Use noon local time to get correct UTC day index (noon trick)
                    val noon = (day.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val dayEpoch = noon.timeInMillis / 86_400_000L
                    dayEpoch in evStartEpoch..evEndEpoch
                } else {
                    ev.startMillis < localDayEndMs && ev.endMillis > localDayStartMs
                }
                if (overlaps) map.getOrPut(idx) { mutableListOf() }.add(ev)
            }
        }
        cachedWeekEvents = (0..6).associateWith { map[it]?.toList().orEmpty() }

        // Month window ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â always fetch the whole visible month grid:
        // Monday of the first visible week through Monday after the last visible week.
        val firstOfMonth = (selectedDay.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val lastOfMonth = (firstOfMonth.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val queryStart = startOfConfiguredWeek(firstOfMonth)
        val queryEnd = startOfConfiguredWeek(lastOfMonth).apply {
            add(Calendar.DAY_OF_MONTH, 7)
        }

        cachedMonthEvents = r.getEvents(queryStart.timeInMillis, queryEnd.timeInMillis, enabled)
    }


    private fun dumpWeekDebug() {
        val tag = "MiniCalDebug"
        val days = weekDays()

        android.util.Log.i(tag, "========== WEEK DEBUG ==========")
        android.util.Log.i(tag, "Selected day: ${dateFmt.format(Date(selectedDay.timeInMillis))}")
        android.util.Log.i(tag, "Screen: $screen  pageOffset=$pageOffset")

        for (i in days.indices) {
            val cal = days[i]
            val dayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            android.util.Log.i(tag, "--- Day $i : $dayStr ---")

            val events = cachedWeekEvents[i].orEmpty()
            if (events.isEmpty()) {
                android.util.Log.i(tag, "  (no events)")
                continue
            }

            for (ev in events.sortedWith(compareBy<CalendarRepository.Event>({ !it.isAllDay }, { it.startMin }, { it.endMin }, { it.title }))) {
                if (ev.isAllDay) {
                    android.util.Log.i(
                        tag,
                        "  ALLDAY  ${ev.title}  epoch ${ev.startDayEpoch}..${ev.endDayEpoch}"
                    )
                } else {
                    val sh = ev.startMin / 60
                    val sm = ev.startMin % 60
                    val eh = ev.endMin / 60
                    val em = ev.endMin % 60
                    android.util.Log.i(
                        tag,
                        "  ${"%02d:%02d".format(sh, sm)}-${"%02d:%02d".format(eh, em)}  ${ev.title}"
                    )
                }
            }
        }

        android.util.Log.i(tag, "================================")
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Capture helpers ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun locale(): Locale =
        resources.configuration.locales[0] ?: Locale.getDefault()

    private data class EventFormData(
        val calendarId: Long,
        val title: String,
        val startDateYYMMDD: String,
        val endDateYYMMDD: String,
        val startTimeHHMM: String,
        val endTimeHHMM: String,
        val location: String,
        val description: String,
        val rrule: String?,
        val reminderMinutes: Int?,
        val attendeeEmails: List<String> = emptyList()
    )

    private data class EventFormPrefill(
        val calendarId: Long?,
        val title: String,
        val startDateYYMMDD: String,
        val endDateYYMMDD: String,
        val startTimeHHMM: String,
        val endTimeHHMM: String,
        val location: String,
        val description: String,
        val repeatLabel: String,
        val reminderLabel: String,
        val attendeeEmails: List<String> = emptyList()
    )

    private fun selectedYYMMDD(): String {
        val y = selectedDay.get(Calendar.YEAR) % 100
        val m = selectedDay.get(Calendar.MONTH) + 1
        val d = selectedDay.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%02d%02d%02d", y, m, d)
    }

    private fun nextHalfHourRange(): Pair<String, String> {
        val now = Calendar.getInstance()
        val start = now.clone() as Calendar
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)
        val minute = start.get(Calendar.MINUTE)
        if (minute < 30) {
            start.set(Calendar.MINUTE, 30)
        } else {
            start.add(Calendar.HOUR_OF_DAY, 1)
            start.set(Calendar.MINUTE, 0)
        }
        val end = start.clone() as Calendar
        end.add(Calendar.HOUR_OF_DAY, 1)
        val startStr = String.format(Locale.US, "%02d%02d", start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
        val endStr = String.format(Locale.US, "%02d%02d", end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
        return startStr to endStr
    }

    private fun parseYYMMDD(s: String): Calendar? {
        val clean = s.trim()
        if (!Regex("""^\d{6}$""").matches(clean)) return null
        val y = 2000 + clean.substring(0, 2).toInt()
        val m = clean.substring(2, 4).toInt() - 1
        val d = clean.substring(4, 6).toInt()
        return Calendar.getInstance().apply {
            set(y, m, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun parseHHMM(s: String, baseDate: Calendar): Calendar? {
        val clean = s.trim()
        if (!Regex("""^\d{4}$""").matches(clean)) return null
        val h = clean.substring(0, 2).toInt()
        val m = clean.substring(2, 4).toInt()
        if (h !in 0..23 || m !in 0..59) return null
        return (baseDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun repeatOptions(): List<String> = resources.getStringArray(R.array.repeat_labels).toList()

    private fun repeatLabelToRRule(label: String): String? = when (label.trim()) {
        getString(R.string.label_daily) -> "FREQ=DAILY"
        getString(R.string.label_weekly) -> "FREQ=WEEKLY"
        getString(R.string.label_monthly) -> "FREQ=MONTHLY"
        getString(R.string.label_yearly) -> "FREQ=YEARLY"
        else -> null
    }

    private fun rruleToRepeatLabel(rrule: String?): String {
        val normalized = rrule?.uppercase(Locale.US).orEmpty()
        return when {
            normalized.contains("FREQ=DAILY") -> getString(R.string.label_daily)
            normalized.contains("FREQ=WEEKLY") -> getString(R.string.label_weekly)
            normalized.contains("FREQ=MONTHLY") -> getString(R.string.label_monthly)
            normalized.contains("FREQ=YEARLY") -> getString(R.string.label_yearly)
            else -> getString(R.string.label_none)
        }
    }


    private fun reminderOptions(isAllDay: Boolean): List<String> =
        resources.getStringArray(
            if (isAllDay) R.array.reminder_labels_all_day else R.array.reminder_labels_timed
        ).toList()

    private fun reminderLabelToMinutes(label: String): Int? = when (label.trim()) {
        getString(R.string.label_at_time_of_event) -> 0
        getString(R.string.label_5_min_before) -> 5
        getString(R.string.label_10_min_before) -> 10
        getString(R.string.label_15_min_before) -> 15
        getString(R.string.label_30_min_before) -> 30
        getString(R.string.label_1_hour_before) -> 60
        getString(R.string.label_1_day_before) -> 1440
        getString(R.string.label_2_days_before) -> 2880
        getString(R.string.label_1_week_before) -> 10080
        else -> null
    }

    private fun minutesToReminderLabel(minutes: Int?, isAllDay: Boolean): String {
        if (minutes == null) return getString(R.string.label_none)
        val options = reminderOptions(isAllDay)
        val candidate = when (minutes) {
            0 -> getString(R.string.label_at_time_of_event)
            5 -> getString(R.string.label_5_min_before)
            10 -> getString(R.string.label_10_min_before)
            15 -> getString(R.string.label_15_min_before)
            30 -> getString(R.string.label_30_min_before)
            60 -> getString(R.string.label_1_hour_before)
            1440 -> getString(R.string.label_1_day_before)
            2880 -> getString(R.string.label_2_days_before)
            10080 -> getString(R.string.label_1_week_before)
            else -> getString(R.string.label_none)
        }
        return if (candidate in options) candidate else getString(R.string.label_none)
    }

    private fun defaultEventPrefill(isAllDay: Boolean): EventFormPrefill {
        val dateStr = selectedYYMMDD()
        val (startT, endT) = nextHalfHourRange()
        return EventFormPrefill(
            calendarId = writeCalendarId,
            title = "",
            startDateYYMMDD = dateStr,
            endDateYYMMDD = dateStr,
            startTimeHHMM = if (isAllDay) "0000" else startT,
            endTimeHHMM = if (isAllDay) "0000" else endT,
            location = "",
            description = "",
            repeatLabel = getString(R.string.label_none),
            reminderLabel = if (isAllDay) getString(R.string.label_none) else getString(R.string.label_10_min_before)
        )
    }

    private fun parseHHMMPartsOrDefault(value: String, defaultHour: Int = 9, defaultMinute: Int = 0): Pair<Int, Int> {
        val clean = value.trim()
        if (!Regex("""^\d{4}$""").matches(clean)) return defaultHour to defaultMinute
        val h = clean.substring(0, 2).toIntOrNull() ?: return defaultHour to defaultMinute
        val m = clean.substring(2, 4).toIntOrNull() ?: return defaultHour to defaultMinute
        return if (h in 0..23 && m in 0..59) h to m else defaultHour to defaultMinute
    }

    private fun showTimePickerForField(field: EditText) {
        val (hour, minute) = parseHHMMPartsOrDefault(field.text?.toString().orEmpty())
        TimePickerDialog(
            this,
            { _, pickedHour, pickedMinute ->
                field.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
            },
            hour,
            minute,
            true
        ).show()
    }
    private fun showDatePickerForField(field: EditText, onPicked: (() -> Unit)? = null) {
        val parsed = parseYYMMDD(field.text?.toString().orEmpty()) ?: selectedDay
        val year = parsed.get(Calendar.YEAR)
        val month = parsed.get(Calendar.MONTH)
        val day = parsed.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(
            this,
            { _, pickedYear, pickedMonth, pickedDay ->
                val yy = pickedYear % 100
                field.setText(String.format(Locale.US, "%02d%02d%02d", yy, pickedMonth + 1, pickedDay))
                onPicked?.invoke()
            },
            year,
            month,
            day
        ).show()
    }


    private fun saveEventForm(form: EventFormData, isAllDay: Boolean, editEventId: Long? = null): String? {
        val r = repo ?: return getString(R.string.label_no_calendar_access)
        if (enabledCalendarIds.isEmpty()) return getString(R.string.label_no_calendars_enabled)
        val title = form.title.trim()
        if (title.isBlank()) return getString(R.string.label_no_title)

        val startDate = parseYYMMDD(form.startDateYYMMDD) ?: return getString(R.string.label_bad_start_date, form.startDateYYMMDD)
        val endDate = parseYYMMDD(form.endDateYYMMDD) ?: return getString(R.string.label_bad_end_date, form.endDateYYMMDD)
        val location = form.location.trim()
        val description = form.description.trim()

        return if (isAllDay) {
            if (endDate.timeInMillis < startDate.timeInMillis) return getString(R.string.label_end_date_before_start)
            fun toUtcMidnight(cal: Calendar): Long {
                val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utc.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
                utc.set(Calendar.MILLISECOND, 0)
                return utc.timeInMillis
            }
            val startUtc = toUtcMidnight(startDate)
            val endUtc = toUtcMidnight(endDate) + 86_400_000L
            val ok = if (editEventId != null) {
                r.updateAllDayEvent(
                    eventId = editEventId,
                    calendarId = form.calendarId,
                    title = title,
                    startDayMillis = startUtc,
                    endDayMillis = endUtc,
                    description = description,
                    location = location,
                    rrule = form.rrule,
                    reminderMinutes = form.reminderMinutes,
                    attendeeEmails = form.attendeeEmails
                )
            } else {
                r.insertAllDayEvent(
                    calendarId = form.calendarId,
                    title = title,
                    startDayMillis = startUtc,
                    endDayMillis = endUtc,
                    description = description,
                    location = location,
                    rrule = form.rrule,
                    reminderMinutes = form.reminderMinutes,
                    attendeeEmails = form.attendeeEmails
                ) != null
            }
            if (ok) {
                reloadEvents()
                AlarmScheduler.scheduleNext(this)
                null
            } else if (editEventId != null) {
                getString(R.string.label_update_failed)
            } else {
                getString(R.string.label_save_failed)
            }
        } else {
            val startFull = parseHHMM(form.startTimeHHMM, startDate) ?: return getString(R.string.label_bad_start_time, form.startTimeHHMM)
            val endFull = parseHHMM(form.endTimeHHMM, endDate) ?: return getString(R.string.label_bad_end_time, form.endTimeHHMM)
            if (endFull.timeInMillis <= startFull.timeInMillis) return getString(R.string.label_end_must_be_after_start)
            val ok = if (editEventId != null) {
                r.updateTimedEvent(
                    eventId = editEventId,
                    calendarId = form.calendarId,
                    title = title,
                    startMillis = startFull.timeInMillis,
                    endMillis = endFull.timeInMillis,
                    description = description,
                    location = location,
                    rrule = form.rrule,
                    reminderMinutes = form.reminderMinutes,
                    attendeeEmails = form.attendeeEmails
                )
            } else {
                r.insertTimedEvent(
                    calendarId = form.calendarId,
                    title = title,
                    startMillis = startFull.timeInMillis,
                    endMillis = endFull.timeInMillis,
                    description = description,
                    location = location,
                    rrule = form.rrule,
                    reminderMinutes = form.reminderMinutes,
                    attendeeEmails = form.attendeeEmails
                ) != null
            }
            if (ok) {
                reloadEvents()
                AlarmScheduler.scheduleNext(this)
                null
            } else if (editEventId != null) {
                getString(R.string.label_update_failed)
            } else {
                getString(R.string.label_save_failed)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (numberBuffer.isNotEmpty()) {
                val num = numberBuffer.toIntOrNull()
                if (num != null && num >= 1 && num <= 99) {
                    selectedSlot = num - 1
                    lastKey = "selected: $num"
                } else {
                    lastKey = "select: $numberBuffer (invalid)"
                }
                numberBuffer = ""
                render()
                return true
            }

            if (selectedSlot == null) {
                lastKey = "enter (no selection)"
                render()
                return true
            }

            navigateToScreen(Screen.EVENT, "open event")
            clearDeleteState()
            render()
            return true
        }

        val uni = event.unicodeChar
        if (uni == 0) return super.dispatchKeyEvent(event)

        val ch = uni.toChar()

        if (ch.isDigit()) {
            if (screen == Screen.EVENT && recurringDeleteMenuPending) {
                val ev = selectedEvent
                val choice = ch.digitToIntOrNull()
                if (ev != null && choice != null) {
                    val ok = when (choice) {
                        1 -> repo?.deleteSingleOccurrence(ev) ?: false
                        2 -> repo?.deleteThisAndFuture(ev) ?: false
                        3 -> repo?.deleteSeries(ev) ?: false
                        else -> return true
                    }
                    if (ok) {
                        reloadEvents()
                        AlarmScheduler.scheduleNext(this)
                        Toast.makeText(
                            this,
                            when (choice) {
                                1 -> getString(R.string.toast_deleted_this_occurrence)
                                2 -> getString(R.string.toast_deleted_this_and_future)
                                else -> getString(R.string.toast_deleted_whole_series)
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(this, getString(R.string.toast_delete_failed), Toast.LENGTH_SHORT).show()
                    }
                    selectedEvent = null
                    returnToPreviousScreen("delete recurring")
                }
                return true
            }
            if (screen == Screen.SETTINGS) {
                val num = ch.toString().toIntOrNull() ?: 0
                val cals = repo?.getCalendars() ?: emptyList()
                val idx = num - 1
                if (idx in cals.indices) {
                    val id = cals[idx].id
                    if (id in enabledCalendarIds) {
                        enabledCalendarIds.remove(id)
                        prefs.edit().putBoolean("read_calendar_$id", false).apply()
                    } else {
                        enabledCalendarIds.add(id)
                        prefs.edit().putBoolean("read_calendar_$id", true).apply()
                    }
                    reloadEvents()
                    AlarmScheduler.scheduleNext(this)
                    render()
                }
                return true
            }
            numberBuffer += ch
            lastKey = "select: $numberBuffer"
            render()
            return true
        }

        val c = ch.lowercaseChar()
        if (numberBuffer.isNotEmpty()) numberBuffer = ""

        if (!hasCalendarPermissions()) {
            when (c) {
                'i' -> {
                    requestCalendarPermissions()
                    lastKey = "i (request permissions)"
                    render()
                }
                'q' -> {
                    settingsPageOffset = 0
                    goTo(Screen.SETTINGS, "q (settings)")
                }
            }
            return true
        }

        when (c) {
            'a' -> goTo(Screen.MONTH, "a (month)")
            'f' -> goTo(Screen.WEEK, "f (week)")
            'g' -> showCaptureDialog(isAllDay = true)
            'r' -> goTo(Screen.FOCUS, "r (focus)")
            'q' -> {
                settingsPageOffset = 0
                goTo(Screen.SETTINGS, "q (settings)")
            }
            //'v' -> {
            //    showDebug = !showDebug
            //    lastKey = "v (debug ${if (showDebug) "on" else "off"})"
            //    render()
            //}
            'k' -> {
                dumpWeekDebug()
                lastKey = "k (week debug dumped)"
                render()
            }

            'w' -> {
                if (screen == Screen.SETTINGS) {
                    val cals = repo?.getCalendars()?.filter { it.id in enabledCalendarIds } ?: emptyList()
                    if (cals.isNotEmpty()) {
                        val curr = cals.indexOfFirst { it.id == writeCalendarId }
                        writeCalendarId = cals[(curr + 1) % cals.size].id
                        writeCalendarId?.let { prefs.edit().putLong("write_calendar_id", it).apply() }
                        AlarmScheduler.scheduleNext(this)
                        render()
                    }
                }
            }

            'n' -> {
                if (screen == Screen.SETTINGS) {
                    val newValue = !alarmEngineEnabled()
                    setAlarmEngineEnabled(newValue)

                    if (newValue) {
                        ensureExactAlarmAccess()
                        ensureNotificationPermission()
                        ensureExactAlarmAccess()
                        AlarmScheduler.scheduleNext(this)
                    } else {
                        AlarmScheduler.cancelAll(this)
                    }
                    render()
                }
            }

            'm' -> {
                if (screen == Screen.SETTINGS) {
                    cycleAlarmFallbackMinutes()
                    AlarmScheduler.scheduleNext(this)
                    render()
                }
            }

            'y' -> {
                if (screen == Screen.SETTINGS) {
                    cycleWeekStartPreference()
                    reloadEvents()
                    pageOffset = 0
                    lastKey = "y (week start ${weekStartLabel()})"
                    render()
                }
            }

            't' -> {
                jumpToToday()
            }

            'i' -> {
                if (screen == Screen.SETTINGS) {
                    requestCalendarPermissions()
                    lastKey = "i (request permissions)"
                    render()
                }
            }

            's' -> {
                selectedDay.add(Calendar.DAY_OF_MONTH, -1)
                selectedSlot = null        // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â Ãƒâ€šÃ‚Â istÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤llet fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶r clearSelection()
                //clearSelection()
                reloadEvents()
                lastKey = "s (day -1)"
                render()
            }

            'e' -> {
                if (screen == Screen.EVENT) {
                    val ev = selectedEvent
                    if (ev != null) {
                        val startCal = Calendar.getInstance().apply { timeInMillis = ev.startMillis }
                        val endCal = Calendar.getInstance().apply {
                            timeInMillis = if (ev.isAllDay) ev.endMillis - 1 else ev.endMillis
                        }
                        fun fmtDate(cal: Calendar): String {
                            val y = cal.get(Calendar.YEAR) % 100
                            val m = cal.get(Calendar.MONTH) + 1
                            val d = cal.get(Calendar.DAY_OF_MONTH)
                            return String.format(Locale.US, "%02d%02d%02d", y, m, d)
                        }
                        val prefill = EventFormPrefill(
                            calendarId = ev.calendarId,
                            title = ev.title,
                            startDateYYMMDD = fmtDate(startCal),
                            endDateYYMMDD = fmtDate(endCal),
                            startTimeHHMM = String.format(Locale.US, "%02d%02d", startCal.get(Calendar.HOUR_OF_DAY), startCal.get(Calendar.MINUTE)),
                            endTimeHHMM = String.format(Locale.US, "%02d%02d", endCal.get(Calendar.HOUR_OF_DAY), endCal.get(Calendar.MINUTE)),
                            location = ev.location,
                            description = ev.description,
                            repeatLabel = rruleToRepeatLabel(ev.rrule),
                            reminderLabel = minutesToReminderLabel(ev.reminderMinutes, ev.isAllDay),
                            attendeeEmails = repo?.getAttendees(ev.id)
                                ?.map { it.email }
                                ?.filter { it.isNotBlank() }
                                .orEmpty()
                        )
                        showCaptureDialog(isAllDay = ev.isAllDay, prefill = prefill, editEventId = ev.id)
                    }
                }
            }

            'd' -> {
                when {
                    screen == Screen.EVENT && recurringDeleteMenuPending -> {
                        clearDeleteState()
                        lastKey = "delete cancelled"
                        render()
                    }
                    screen == Screen.EVENT && eventDeletePending -> {
                        val ev = selectedEvent
                        if (ev != null) {
                            val ok = repo?.deleteEvent(ev.id) ?: false
                            if (ok) {
                                reloadEvents()
                                AlarmScheduler.scheduleNext(this)
                                Toast.makeText(this, getString(R.string.toast_deleted_event, ev.title), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, getString(R.string.toast_delete_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                        selectedEvent = null
                        returnToPreviousScreen("delete event")
                    }
                    screen == Screen.EVENT -> {
                        val ev = selectedEvent
                        if (isRecurringEvent(ev)) {
                            clearDeleteState()
                            recurringDeleteMenuPending = true
                            lastKey = "d (recurring delete scope)"
                        } else {
                            clearDeleteState()
                            eventDeletePending = true
                            lastKey = "d (delete confirm?)"
                        }
                        render()
                    }
                    else -> {
                        selectedDay.add(Calendar.DAY_OF_MONTH, 1)
                        selectedSlot = null
                        reloadEvents()
                        lastKey = "d (day +1)"
                        render()
                    }
                }
            }

            'z' -> {
                when (screen) {
                    Screen.MONTH -> selectedDay.add(Calendar.MONTH, -1)
                    Screen.WEEK  -> selectedDay.add(Calendar.WEEK_OF_YEAR, -1)
                    Screen.FOCUS -> selectedDay.add(Calendar.DAY_OF_MONTH, -1)
                    else         -> selectedDay.add(Calendar.DAY_OF_MONTH, -1)
                }
                pageOffset = 0
                clearSelection()
                reloadEvents()
                lastKey = "z (prev)"
                render()
            }

            'x' -> {
                when (screen) {
                    Screen.MONTH -> selectedDay.add(Calendar.MONTH, 1)
                    Screen.WEEK  -> selectedDay.add(Calendar.WEEK_OF_YEAR, 1)
                    Screen.FOCUS -> selectedDay.add(Calendar.DAY_OF_MONTH, 1)
                    else         -> selectedDay.add(Calendar.DAY_OF_MONTH, 1)
                }
                pageOffset = 0
                clearSelection()
                reloadEvents()
                lastKey = "x (next)"
                render()
            }

            // Paging within week/day/settings view
            'p' -> {
                if (screen == Screen.SETTINGS) {
                    scrollSettingsPage(1)
                    lastKey = "p (settings page down)"
                } else {
                    pageOffset++
                    lastKey = "p (page down)"
                }
                render()
            }
            'o' -> {
                if (screen == Screen.SETTINGS) {
                    scrollSettingsPage(-1)
                    lastKey = "o (settings page up)"
                } else {
                    if (pageOffset > 0) pageOffset--
                    lastKey = "o (page up)"
                }
                render()
            }

            'h' -> {
                showFooter = !showFooter
                lastKey = "h (footer ${if (showFooter) "on" else "off"})"
                render()
            }
            'c' -> showCaptureDialog(isAllDay = false)



            'b' -> {
                handleBackNavigation(fromSystem = false)
            }
        }

        return true
    }

    private fun clearSelection() {
        selectedSlot = null
        numberBuffer = ""
        pageOffset = 0
    }

    private fun jumpToToday() {
        selectedDay = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        clearSelection()
        reloadEvents()
        lastKey = "t (today)"
        render()
    }

    private fun navigateToScreen(target: Screen, note: String, pushCurrent: Boolean = true) {
        if (pushCurrent && screen != target) {
            screenHistory.addLast(screen)
        }
        previousScreen = screen
        screen = target
        selectedSlot = null
        numberBuffer = ""
        if (target != Screen.SETTINGS) settingsPageOffset = 0
        lastKey = note
    }

    private fun popPreviousScreenOrFallback(): Screen {
        val target = if (screenHistory.isNotEmpty()) screenHistory.removeLast() else previousScreen
        previousScreen = if (screenHistory.isNotEmpty()) screenHistory.last() else target
        return target
    }

    private fun returnToPreviousScreen(note: String, reloadAfter: Boolean = false) {
        screen = popPreviousScreenOrFallback()
        selectedSlot = null
        clearDeleteState()
        numberBuffer = ""
        lastKey = note
        if (reloadAfter) reloadEvents()
        render()
    }

    private fun handleBackNavigation(fromSystem: Boolean) {
        if (screenHistory.isEmpty()) {
            if (fromSystem) {
                finish()
            } else {
                selectedSlot = null
                clearDeleteState()
                lastKey = "b (back)"
                render()
            }
            return
        }

        val wasEvent = screen == Screen.EVENT
        returnToPreviousScreen(
            note = if (fromSystem) "system back" else "b (back)",
            reloadAfter = wasEvent
        )
    }

    private fun goTo(s: Screen, note: String) {
        navigateToScreen(s, note)
        reloadEvents()
        render()
    }
    private fun parseHHMMSafe(text: String): Pair<Int, Int> {
        val clean = text.trim()
        return if (clean.length == 4) {
            val h = clean.substring(0, 2).toIntOrNull() ?: 0
            val m = clean.substring(2, 4).toIntOrNull() ?: 0
            h to m
        } else {
            0 to 0
        }
    }

    private fun formatHHMM(h: Int, m: Int): String {
        return "%02d%02d".format(h, m)
    }

    private fun addMinutesToField(field: EditText, minutes: Int) {
        val (h, m) = parseHHMMSafe(field.text.toString())
        val total = h * 60 + m + minutes
        val newH = ((total / 60) % 24 + 24) % 24
        val newM = ((total % 60) + 60) % 60
        field.setText(formatHHMM(newH, newM))
    }

    private fun setEndFromStart(startField: EditText, endField: EditText, minutes: Int) {
        val (h, m) = parseHHMMSafe(startField.text.toString())
        val total = h * 60 + m + minutes
        val newH = ((total / 60) % 24 + 24) % 24
        val newM = ((total % 60) + 60) % 60
        endField.setText(formatHHMM(newH, newM))
    }
    private fun setFieldFromFieldPlusMinutes(sourceField: EditText, targetField: EditText, minutes: Int) {
        val (h, m) = parseHHMMSafe(sourceField.text.toString())
        val total = h * 60 + m + minutes
        val newH = ((total / 60) % 24 + 24) % 24
        val newM = ((total % 60) + 60) % 60
        targetField.setText(formatHHMM(newH, newM))
    }

    private fun renderMissingPermissionsView() {
        applyAppThemeChrome()
        header.text = getString(R.string.label_permissions)
        monthCanvas?.visibility = View.GONE
        weekCanvas?.visibility = View.GONE
        dayCanvas?.visibility = View.GONE
        settingsScrollView?.visibility = View.GONE
        body.visibility = View.VISIBLE
        body.requestFocus()
        body.textSize = 14f
        body.text = buildString {
            appendLine(getString(R.string.label_calendar_access_required))
            appendLine()
            appendLine(getString(R.string.label_grant_permissions))
            appendLine("- READ_CALENDAR")
            appendLine("- WRITE_CALENDAR")
            appendLine()
            appendLine(getString(R.string.label_press_i_permission))
            appendLine(getString(R.string.label_press_q_settings))
        }
    }

    private fun render() {
        applyAppThemeChrome()
        if (!hasCalendarPermissions() && screen != Screen.SETTINGS) {
            renderMissingPermissionsView()
            return
        }

        if (screen == Screen.MONTH) {
            val mf = java.text.SimpleDateFormat("MMMM yyyy", locale())
            val firstOfMonth = (selectedDay.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            header.text = mf.format(firstOfMonth.time)
                .replaceFirstChar { it.uppercaseChar() }
        } else if (screen == Screen.FOCUS) {
        } else {
            val weekNum = weekNumberFor(selectedDay)
            header.text = when (screen) {
                Screen.WEEK     -> {
                    val wf = java.text.SimpleDateFormat("MMM yyyy", locale())
                    "${getString(R.string.label_week_number, weekNum)} - ${wf.format(selectedDay.time)}"
                }
                Screen.SETTINGS -> getString(R.string.label_settings)
                else            -> screen.name.lowercase()
            }
        }


        if (screen == Screen.MONTH) {
            body.visibility = View.GONE
            settingsScrollView?.visibility = View.GONE
            weekCanvas?.visibility = View.GONE
            dayCanvas?.visibility = View.GONE
            if (monthCanvas == null) {
                monthCanvas = MonthCanvasView(this).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                bodyContainer.addView(monthCanvas)
            }
            monthCanvas!!.visibility = View.VISIBLE
            monthCanvas!!.requestFocus()
            monthCanvas!!.update(selectedDay, cachedMonthEvents, showFooter, resolvedFirstDayOfWeek(), darkModeEnabled())
            monthCanvas!!.setOnDayClick { day ->
                selectedDay = day
                navigateToScreen(Screen.WEEK, "tap day -> week")
                pageOffset = 0
                reloadEvents()
                render()
            }
        } else if (screen == Screen.WEEK) {
            body.visibility = View.GONE
            settingsScrollView?.visibility = View.GONE
            monthCanvas?.visibility = View.GONE
            dayCanvas?.visibility = View.GONE
            if (weekCanvas == null) {
                weekCanvas = WeekCanvasView(this).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                bodyContainer.addView(weekCanvas)
            }
            weekCanvas!!.visibility = View.VISIBLE
            weekCanvas!!.requestFocus()
            weekCanvas!!.update(selectedDay, cachedWeekEvents, repo != null, pageOffset, showFooter, resolvedFirstDayOfWeek(), darkModeEnabled())
            weekCanvas!!.onEventClick = { event ->
                selectedEvent = event
                clearDeleteState()
                navigateToScreen(Screen.EVENT, "tap event")
                render()
            }
            weekCanvas!!.onDayTap = { day ->
                selectedDay = day
                selectedEvent = null
                clearDeleteState()
                navigateToScreen(Screen.FOCUS, "tap day -> focus")
                pageOffset = 0
                render()
            }
        } else if (screen == Screen.FOCUS) {
            body.visibility = View.GONE
            settingsScrollView?.visibility = View.GONE
            monthCanvas?.visibility = View.GONE
            weekCanvas?.visibility = View.GONE
            if (dayCanvas == null) {
                dayCanvas = DayListCanvasView(this).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                bodyContainer.addView(dayCanvas)
            }
            dayCanvas!!.visibility = View.VISIBLE
            dayCanvas!!.requestFocus()
            dayCanvas!!.update(selectedDay, getFocusEventsForSelectedDay(), calendarMap, showFooter, pageOffset, darkModeEnabled())
            val dayFmt = java.text.SimpleDateFormat("EEE d MMM", locale())
            val focusHeader = dayFmt.format(selectedDay.time).replaceFirstChar { it.uppercaseChar() }
            val pageInfo = dayCanvas!!.headerPageIndicator()
            header.text = if (pageInfo != null) "$focusHeader $pageInfo" else focusHeader
            dayCanvas!!.onEventClick = { event ->
                selectedEvent = event
                clearDeleteState()
                navigateToScreen(Screen.EVENT, "tap event")
                render()
            }
        } else if (screen == Screen.SETTINGS) {
            monthCanvas?.visibility = View.GONE
            weekCanvas?.visibility = View.GONE
            dayCanvas?.visibility = View.GONE
            body.visibility = View.GONE
            renderSettingsPanel()
            settingsScrollView?.visibility = View.VISIBLE
            settingsScrollView?.requestFocus()
        } else {
            monthCanvas?.visibility = View.GONE
            weekCanvas?.visibility = View.GONE
            dayCanvas?.visibility = View.GONE
            settingsScrollView?.visibility = View.GONE
            body.visibility = View.VISIBLE
            body.requestFocus()
            // Anpassa utseende per skÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤rm
            if (screen == Screen.FOCUS||screen == Screen.SETTINGS) {
                body.textSize = 14f   // justera efter smak, default ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤r ~14f
            } else {
                body.textSize = 14f
            }
            val raw = buildString {
                if (showDebug) {
                    appendLine("last key: $lastKey")
                    appendLine()
                    appendLine("selected day: ${dateFmt.format(Date(selectedDay.timeInMillis))}")
                    appendLine()
                }

                when (screen) {
                    Screen.WEEK -> renderWeekView(this)

                    Screen.FOCUS -> renderDayView(this)

                    Screen.EVENT -> renderEventView(this)

                    Screen.CAPTURE_TIMED, Screen.CAPTURE_ALLDAY -> { /* handled by dialog */ }

                    Screen.SETTINGS -> renderSettingsView(this)

                    else -> {}
                }
            }
            body.movementMethod = if (screen == Screen.EVENT) android.text.method.ScrollingMovementMethod.getInstance() else null
            if (body.text.toString() != raw) {
                body.text = raw
                body.scrollTo(0, 0)
            }
        }
        maybeFlashEpaperOnDarkViewChange()

    }

    // -----------------------
    // WEEK VIEW rendering
    // -----------------------

    private fun renderWeekView(out: StringBuilder) {

        val days = weekDays()

        val timeColW = 6
        val sepW = 1
        val usable = (bodyCols - timeColW - sepW).coerceAtLeast(28)
        val dayW = ((usable - 1) / 7).coerceIn(6, 16)

        // Header row: week-num + month in time col, then M23 T24 ...
        val weekNum = weekNumberFor(days[0])
        val monthLabel = SimpleDateFormat("MMM", locale()).format(selectedDay.time)
        val leftHeader = "${getString(R.string.label_week_number, weekNum)} ${monthLabel.take(3)}"
        val headerRow = buildString {
            append(leftHeader.take(timeColW).padEnd(timeColW))
            append(" ".repeat(sepW))
            days.forEach { cal ->
                val dow = dow1Fmt.format(cal.time).replace(".", "").trim().take(1).uppercase(locale())
                val dayNum = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                append(center("$dow$dayNum", dayW))
            }
        }
        out.appendLine(headerRow)

        // Selected day marker row
        val markerRow = buildString {
            append(" ".repeat(timeColW))
            append(" ".repeat(sepW))
            days.forEach { cal ->
                val mark = if (sameDay(cal, selectedDay)) "^" else " "
                append(center(mark, dayW))
            }
        }
        out.appendLine(markerRow)
        out.appendLine()

        // Use live events if available, otherwise fall back to dummy data
        val byDay: Map<Int, List<CalEvent>> = if (repo != null && cachedWeekEvents.isNotEmpty()) {
            (0..6).associateWith { idx ->
                cachedWeekEvents[idx].orEmpty().map { ev ->
                    CalEvent(
                        dayIndex   = idx,
                        title      = ev.title,
                        isAllDay   = ev.isAllDay,
                        startMin   = ev.startMin,
                        endMin     = ev.endMin,
                        startDayEpoch = ev.startDayEpoch,
                        endDayEpoch   = ev.endDayEpoch
                    )
                }
            }
        } else {
            dummyEventsForWeek(days)
        }

        // All-day block
        val allDayRows = 3
        val allDayCols: List<List<String>> = (0..6).map { di ->
            val events = byDay[di].orEmpty().filter { it.isAllDay }
            val lines = events.flatMap { ev ->
                val wrapped = wrap(ev.title, dayW - 1, maxLines = 3)
                wrapped.mapIndexed { idx, s ->
                    val prefix = if (idx == 0) "|" else " "
                    (prefix + s).padEnd(dayW).take(dayW)
                }
            }
            padToSize(lines.take(allDayRows), allDayRows)
        }

        for (r in 0 until allDayRows) {
            val line = buildString {
                append(" ".repeat(timeColW))
                append(" ".repeat(sepW))
                for (d in 0..6) {
                    val cell = allDayCols[d].getOrNull(r).orEmpty()
                    append(cell.padEnd(dayW))
                }
            }
            out.appendLine(line.trimEnd())
        }

        val sep = buildString {
            append(" ".repeat(timeColW))
            append(" ".repeat(sepW))
            append("-".repeat(dayW * 7).take(dayW * 7))
        }
        out.appendLine(sep)

        // Timed events
        val timedByDay: List<List<CalEvent>> = (0..6).map { di ->
            byDay[di].orEmpty().filter { !it.isAllDay }.sortedWith(
                compareBy<CalEvent> { it.startMin }.thenBy { it.endMin }.thenBy { it.title.lowercase(Locale.getDefault()) }
            )
        }

        val (minHour, maxHourExclusive) = computeHourWindow(timedByDay)

        // Build a sequence of "slots" to render:
        //  - active hours (have events overlapping) ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ rendered normally
        //  - runs of empty hours ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ collapsed to a single gap row

        val slots = mutableListOf<WeekSlot>()
        val hourHasEvents: (Int) -> Boolean = { h ->
            val hStart = h * 60
            val hEnd = hStart + 60
            timedByDay.any { dayEvents ->
                dayEvents.any { ev -> ev.startMin < hEnd && ev.endMin > hStart }
            }
        }

        var gapStart: Int? = null

        for (h in minHour until maxHourExclusive) {
            if (hourHasEvents(h)) {
                if (gapStart != null) {
                    slots.add(WeekSlot.Gap(gapStart!!, h - 1))
                    gapStart = null
                }
                slots.add(WeekSlot.Active(h))
            } else {
                if (gapStart == null) gapStart = h
            }
        }
        if (gapStart != null) {
            slots.add(WeekSlot.Gap(gapStart!!, maxHourExclusive - 1))
        }

        val activeHeights = mutableMapOf<Int, Int>()
        for (slot in slots) {
            if (slot is WeekSlot.Active) {
                var maxLines = 1
                for (dayIdx in 0..6) {
                    val lines = buildHourCellLines(
                        hour = slot.hour,
                        dayEvents = timedByDay[dayIdx],
                        colWidth = dayW,
                        emitContinuation = true
                    ).size
                    maxLines = max(maxLines, lines.coerceAtLeast(1))
                }
                activeHeights[slot.hour] = maxLines
            }
        }

        // Render slots
        for (slot in slots) {
            when (slot) {
                is WeekSlot.Active -> {
                    val h = slot.hour
                    val blockH = activeHeights[h] ?: 1

                    val dayLines: List<List<String>> = (0..6).map { dayIdx ->
                        val raw = buildHourCellLines(
                            hour = h,
                            dayEvents = timedByDay[dayIdx],
                            colWidth = dayW,
                            emitContinuation = true
                        )
                        padToSize(raw, blockH).map { it.padEnd(dayW).take(dayW) }
                    }

                    for (r in 0 until blockH) {
                        val line = buildString {
                            if (r == 0) append(formatMinute(h * 60).padEnd(timeColW))
                            else append(" ".repeat(timeColW))
                            append(" ".repeat(sepW))
                            for (d in 0..6) append(dayLines[d][r])
                        }
                        out.appendLine(line.trimEnd())
                    }
                }

                is WeekSlot.Gap -> {
                    // Single collapsed row: "08-13" in time column, blank day columns
                    val label = if (slot.from == slot.to)
                        formatMinute(slot.from * 60).trimEnd()
                    else
                        "${slot.from.toString().padStart(2,'0')}-${slot.to.toString().padStart(2,'0')}"

                    val line = buildString {
                        append(label.padEnd(timeColW))
                        // no content, trimEnd will strip trailing spaces
                    }
                    out.appendLine(line.trimEnd())
                }
            }
        }

        out.appendLine()
        out.appendLine(getString(R.string.label_footer_focus))
    }


    private fun isRecurringEvent(ev: CalendarRepository.Event?): Boolean =
        ev?.isRecurring == true

    private fun clearDeleteState() {
        eventDeletePending = false
        recurringDeleteMenuPending = false
    }

    // -----------------------
    // EVENT VIEW rendering
    // -----------------------

    private fun renderEventView(out: StringBuilder) {
        val ev = selectedEvent
        if (ev == null) {
            out.appendLine(getString(R.string.label_no_event_selected))
            out.appendLine()
            out.appendLine(getString(R.string.label_back_hint))
            return
        }
        val timeFmt = SimpleDateFormat("HH:mm", locale())
        val dateFmt = SimpleDateFormat("EEE d MMM yyyy", locale()).apply {
            if (ev.isAllDay) timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        out.appendLine(ev.title)
        out.appendLine()
        calendarMap[ev.calendarId]?.name?.let {
            out.appendLine("${getString(R.string.label_calendar)}: $it")
            out.appendLine()
        }

        if (ev.isAllDay) {
            out.appendLine("${getString(R.string.label_all_day)}: ${dateFmt.format(Date(ev.startMillis))}")
            if (ev.startDayEpoch != ev.endDayEpoch) {
                out.appendLine("  - ${dateFmt.format(Date(ev.endMillis - 1))}")
            }
        } else {
            out.appendLine("${timeFmt.format(Date(ev.startMillis))}-${timeFmt.format(Date(ev.endMillis))}")
            out.appendLine(dateFmt.format(Date(ev.startMillis)))
        }

        if (ev.location.isNotBlank()) {
            out.appendLine()
            out.appendLine("${getString(R.string.label_location)}: ${ev.location}")
        }
        if (ev.isRecurring) {
            out.appendLine()
            val repeat = ev.rrule?.let { rruleToRepeatLabel(it) }.orEmpty()
            out.appendLine(if (repeat.isNotBlank()) "${getString(R.string.label_repeat)}: $repeat"
                else getString(R.string.label_repeat))
        }
        ev.reminderMinutes?.let {
            out.appendLine()
            out.appendLine("${getString(R.string.label_reminder)}: ${minutesToReminderLabel(it, ev.isAllDay)}")
        }
        val description = ev.displayDescription
        if (description.isNotBlank()) {
            out.appendLine()
            out.appendLine(description)
        }

        val attendees = repo?.getAttendees(ev.id).orEmpty()
        if (attendees.isNotEmpty()) {
            out.appendLine()
            out.appendLine(getString(R.string.label_attendees_count, attendees.size))
            for (a in attendees) out.appendLine("  ${a.statusLabel} ${a.displayName}")
        }

        out.appendLine()
        out.appendLine("-".repeat(bodyCols.coerceIn(20, 60)))
        when {
            recurringDeleteMenuPending -> {
                out.appendLine(getString(R.string.label_delete_recurring_event))
                out.appendLine(getString(R.string.label_delete_this_occurrence))
                out.appendLine(getString(R.string.label_delete_this_and_future))
                out.appendLine(getString(R.string.label_delete_whole_series))
                out.appendLine(getString(R.string.label_cancel_hint))
            }
            eventDeletePending -> out.appendLine(getString(R.string.label_delete_confirm, ev.title))
            isRecurringEvent(ev) -> out.appendLine(getString(R.string.label_event_controls_recurring))
            else -> out.appendLine(getString(R.string.label_event_controls_single))
        }
    }

    // -----------------------
    // MONTH VIEW rendering
    // -----------------------

    // renderMonthView removed ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â replaced by MonthCanvasView


    private fun getFocusEventsForSelectedDay(): List<CalendarRepository.Event> {
        val midnight = (selectedDay.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStartMs = midnight.timeInMillis
        val dayEndMs = dayStartMs + 86_400_000L

        val result = mutableListOf<CalendarRepository.Event>()
        val r = repo
        if (r != null) {
            val evs = r.getEvents(dayStartMs, dayEndMs, enabledCalendarIds)
            val noon = (selectedDay.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayEpoch = noon.timeInMillis / 86_400_000L

            for (ev in evs) {
                if (ev.isAllDay) {
                    val evStartEpoch = ev.startMillis / 86_400_000L
                    val evEndEpoch = (ev.endMillis - 1) / 86_400_000L
                    if (dayEpoch in evStartEpoch..evEndEpoch) result.add(ev)
                } else {
                    result.add(ev)
                }
            }
        }
        return result.sortedWith(
            compareBy<CalendarRepository.Event>({ !it.isAllDay }, { if (it.isAllDay) 0 else it.startMin }, { it.endMin }, { it.title.lowercase(Locale.getDefault()) })
        )
    }

    private fun renderDayView(out: StringBuilder) {
        val midnight = (selectedDay.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStartMs = midnight.timeInMillis
        val dayEndMs   = dayStartMs + 86_400_000L

        val allDayEvs = mutableListOf<String>()
        val allTimed  = mutableListOf<CalEvent>()

        if (repo != null) {
            val evs = repo!!.getEvents(dayStartMs, dayEndMs,
                enabledCalendarIds)
            for (ev in evs) {
                if (ev.isAllDay) {
                    val evStartEpoch = ev.startMillis / 86_400_000L
                    val evEndEpoch   = (ev.endMillis - 1) / 86_400_000L

                    val noon = (selectedDay.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 12)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val dayEpoch = noon.timeInMillis / 86_400_000L

                    if (dayEpoch in evStartEpoch..evEndEpoch) {
                        allDayEvs.add(ev.title)
                    }
                } else {
                    allTimed.add(CalEvent(
                        dayIndex      = 0,
                        title         = ev.title,
                        isAllDay      = false,
                        startMin      = ev.startMin,
                        endMin        = ev.endMin,
                        startDayEpoch = ev.startDayEpoch,
                        endDayEpoch   = ev.endDayEpoch
                    ))
                }
            }
            allTimed.sortBy { it.startMin }
        } else {
            // Fallback: dummy data
            val dayEpoch = dayStartMs / 86_400_000L
            val days = weekDays()
            val dayIdx = days.indexOfFirst { sameDay(it, selectedDay) }
            val byDay = dummyEventsForWeek(days)
            val monthEvs = dummyEventsForMonth().filter { ev ->
                ev.startDayEpoch <= dayEpoch && ev.endDayEpoch >= dayEpoch
            }
            for (ev in monthEvs) { if (ev.isAllDay) allDayEvs.add(ev.title) }
            val weekTimedEvs = if (dayIdx >= 0) byDay[dayIdx].orEmpty().filter { !it.isAllDay } else emptyList()
            allTimed.addAll((weekTimedEvs + monthEvs.filter { !it.isAllDay }).sortedBy { it.startMin })
        }

        val linesPerPage = 30   // how many event lines fit before paging
        val contentLines = mutableListOf<String>()

        // All-day section
        if (allDayEvs.isNotEmpty()) {
            contentLines.add("all-day:")
            allDayEvs.forEach { contentLines.add("  | $it") }
            contentLines.add("")
        }

        // Timed events
        if (allTimed.isEmpty() && allDayEvs.isEmpty()) {
            contentLines.add(getString(R.string.label_no_events).lowercase(locale()))
        } else {
            for (ev in allTimed) {
                val start = formatMinute(ev.startMin)
                val end   = formatMinute(ev.endMin)
                val durationMin = ev.endMin - ev.startMin
                val durationStr = when {
                    durationMin <= 0  -> ""
                    durationMin < 60  -> "  (${durationMin}min)"
                    durationMin % 60 == 0 -> "  (${durationMin/60}h)"
                    else -> "  (${durationMin/60}h${durationMin%60}min)"
                }
                contentLines.add("$start-$end  ${ev.title}$durationStr")
            }
        }

        // Paging
        val totalPages = ((contentLines.size - 1) / linesPerPage + 1).coerceAtLeast(1)
        val page = pageOffset.coerceIn(0, totalPages - 1)
        val startLine = page * linesPerPage
        val pageLines = contentLines.drop(startLine).take(linesPerPage)

        pageLines.forEach { out.appendLine(it) }

        // Pad to push footer down
        out.appendLine()

        // Footer
        val pageInfo = if (totalPages > 1) getString(R.string.label_footer_day_page_info) + " ${page+1}/$totalPages" else ""
        out.appendLine(getString(R.string.label_footer_day, pageInfo))
    }

    /**
     * Dummy events for month view.
     * Uses absolute Calendar dates so events can span multiple days/weeks.
     */
    private fun dummyEventsForMonth(): List<CalEvent> {
        val list = mutableListOf<CalEvent>()

        // Use the currently selected year/month for dummy data so it always shows
        val y = selectedDay.get(Calendar.YEAR)
        val m = selectedDay.get(Calendar.MONTH) + 1  // 1-based

        fun cal(day: Int): Calendar = Calendar.getInstance().apply {
            set(y, m - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        fun Long.toEpoch() = this / 86_400_000L
        fun addSpan(title: String, startCal: Calendar, endCal: Calendar, allDay: Boolean = true) {
            list.add(CalEvent(
                dayIndex = 0,
                title = title,
                isAllDay = allDay,
                startDayEpoch = startCal.timeInMillis.toEpoch(),
                endDayEpoch   = endCal.timeInMillis.toEpoch()
            ))
        }
        fun addSingle(title: String, c: Calendar, sh: Int = 0, sm: Int = 0, eh: Int = 0, em: Int = 0) {
            val epoch = c.timeInMillis / 86_400_000L
            list.add(CalEvent(
                dayIndex = 0,
                title = title,
                isAllDay = (sh == 0 && sm == 0 && eh == 0 && em == 0),
                startMin = sh * 60 + sm,
                endMin   = eh * 60 + em,
                startDayEpoch = epoch,
                endDayEpoch   = epoch
            ))
        }

        // All-day singles (day numbers relative to current month)
        addSingle("Ruben de",          cal(23))
        addSingle("Pappa fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶delsedag",  cal(29))
        // Multi-day spanning two weeks
        addSpan("Utvecklingsinit",     cal(18), cal(21))
        addSpan("Konferens Berlin",    cal(25), cal(31))
        // Timed single-day
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te gustav",       cal(25), 9, 30, 9, 55)
        addSingle("Frank klÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤ttring",   cal(29), 13, 0, 15, 0)
        addSingle("Middag Hedvig",     cal(27), 18, 0, 19, 0)
        addSingle("Randi gympa",       cal(24), 20, 0, 20, 30)
        addSingle("komponentkoll",     cal(24), 13, 0, 14, 0)
        addSingle("Nira Control",      cal(25), 10, 0, 11, 0)
        addSingle("Johan fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶delsedag",  cal(25), 20, 0, 21, 0)
        // Overflow test: lots of events same day
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te A",  cal(26), 9, 0, 10, 0)
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te B",  cal(26), 11, 0, 12, 0)
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te C",  cal(26), 14, 0, 15, 0)
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te D",  cal(26), 16, 0, 17, 0)
        addSingle("MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te E",  cal(26), 18, 0, 19, 0)

        return list
    }

    /**
     * Build lines for a given day column at a given hour.
     *
     * For events that start on a non-hour boundary, the start time is prepended
     * to the first content line so the user can see exactly when it begins.
     */
    private fun buildHourCellLines(
        hour: Int,
        dayEvents: List<CalEvent>,
        colWidth: Int,
        emitContinuation: Boolean
    ): List<String> {
        val hourStart = hour * 60
        val hourEnd = hourStart + 60

        val overlapping = dayEvents.filter { ev ->
            ev.startMin < hourEnd && ev.endMin > hourStart
        }
        if (overlapping.isEmpty()) return emptyList()

        val linesOut = mutableListOf<String>()

        for (ev in overlapping) {
            val startsThisHour = ev.startMin in hourStart until hourEnd
            val endsThisHour = ev.endMin in (hourStart + 1)..hourEnd
            val endHour = ev.endMin / 60
            val endMinute = ev.endMin % 60
            val endTxt = String.format(Locale.getDefault(), "%02d:%02d", endHour, endMinute)

            val startOnBoundary = (ev.startMin % 60 == 0)

            if (startsThisHour) {
                val available = (colWidth - 1).coerceAtLeast(4)
                val wrappedTitle = wrap(ev.title, available, maxLines = 6)
                val content = wrappedTitle.toMutableList()

                // Prepend start time on first line if not on the hour boundary
                if (!startOnBoundary) {
                    val startTxt = formatMinute(ev.startMin)
                    // Replace first line with "HH:MM title..." if it fits, otherwise add as own line
                    if (content.isNotEmpty()) {
                        val combined = "$startTxt ${content[0]}"
                        if (combined.length <= available) {
                            content[0] = combined
                        } else {
                            // Put start time on its own line, re-wrap rest
                            content.add(0, startTxt)
                        }
                    } else {
                        content.add(startTxt)
                    }
                }

                // Ensure at least 2 lines for readability
                while (content.size < 2) content.add("")

                content.forEach { linesOut.add(it.padEnd(colWidth).take(colWidth)) }
                // End time on its own line for readability
                if (endsThisHour) {
                    linesOut.add(endTxt.padEnd(colWidth).take(colWidth))
                }

            } else {
                // Continuation from previous hour
                if (emitContinuation) {
                    val marker = if (endsThisHour) "ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ $endTxt" else "ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡"
                    linesOut.add(marker.padEnd(colWidth).take(colWidth))
                }
            }
        }

        return linesOut
    }

    /**
     * Decide what hours we render.
     */
    private fun computeHourWindow(timedByDay: List<List<CalEvent>>): Pair<Int, Int> {
        val all = timedByDay.flatten()
        if (all.isEmpty()) return 8 to 21

        val minStart = all.minOf { it.startMin }
        val maxEnd = all.maxOf { it.endMin }

        val minHour = (minStart / 60).coerceIn(0, 23)
        val maxHourExclusive = ceil(maxEnd / 60.0).toInt().coerceIn(1, 24)

        val uiMin = min(minHour, 8).coerceAtLeast(0)
        val uiMax = max(maxHourExclusive, 21).coerceAtMost(24)

        return uiMin to uiMax
    }

    // -----------------------
    // Calendar helpers
    // -----------------------

    private fun startOfConfiguredWeek(base: Calendar, firstDayOfWeek: Int = resolvedFirstDayOfWeek()): Calendar {
        val cal = configureWeekCalendar(base.clone() as Calendar, firstDayOfWeek)
        val diff = (7 + (cal.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek)) % 7
        cal.add(Calendar.DAY_OF_MONTH, -diff)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun startOfWeek(base: Calendar): Calendar =
        startOfConfiguredWeek(base)

    private fun weekDays(): List<Calendar> {
        val start = startOfWeek(selectedDay)
        return (0..6).map {
            (start.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, it)
            }
        }
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Format minutes-since-midnight as HH:MM.
     * Used for both time column labels and inline start times.
     */
    private fun formatMinute(totalMin: Int): String {
        val h = totalMin / 60
        val m = totalMin % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    }

    private val NOTIFICATION_PERM_REQUEST = 1003

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERM_REQUEST
                )
            }
        }
    }

    // -----------------------
    // Dummy events
    // -----------------------

    private data class CalEvent(
        val dayIndex: Int,
        val title: String,
        val isAllDay: Boolean = false,
        val startMin: Int = 0,
        val endMin: Int = 0,
        val startDayEpoch: Long = 0L,
        val endDayEpoch: Long = 0L
    )

    private fun dummyEventsForWeek(days: List<Calendar>): Map<Int, List<CalEvent>> {
        val map = mutableMapOf<Int, MutableList<CalEvent>>()

        fun addAllDay(dayIdx: Int, title: String) {
            map.getOrPut(dayIdx) { mutableListOf() }
                .add(CalEvent(dayIndex = dayIdx, title = title, isAllDay = true))
        }

        fun addTimed(dayIdx: Int, title: String, sh: Int, sm: Int, eh: Int, em: Int) {
            val s = sh * 60 + sm
            val e = eh * 60 + em
            map.getOrPut(dayIdx) { mutableListOf() }
                .add(CalEvent(dayIndex = dayIdx, title = title, isAllDay = false, startMin = s, endMin = e))
        }

        addAllDay(0, "Ruben de")
        addAllDay(3, "Utvecklingsinit")
        addAllDay(5, "Pappa fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶delsedag")

        addTimed(1, "skriv ut schema s900+ mb", 9, 0, 10, 50)
        addTimed(3, "MÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶te gustav ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶s-", 9, 30, 9, 55)   // non-hour start
        addTimed(3, "Nira Control", 10, 0, 11, 0)

        addTimed(1, "komponentkoll johan hakan", 13, 0, 14, 0)
        addTimed(5, "Frank klÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤ttring", 13, 0, 15, 0)

        addTimed(4, "Middag Hedvig och Ninja", 18, 0, 19, 0)

        addTimed(1, "Randi gympaklÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤der", 20, 0, 20, 30)
        addTimed(3, "Johan fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶delsedag", 20, 0, 21, 0)
        addTimed(5, "Pappa fÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¶delsedag", 20, 0, 21, 0)

        return (0..6).associateWith { idx -> map[idx]?.toList().orEmpty() }
    }

    // -----------------------
    // Text layout helpers
    // -----------------------

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Capture UI helpers ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun showCaptureInput(prefill: String) {
        screen = if (captureIsAllDay) Screen.CAPTURE_ALLDAY else Screen.CAPTURE_TIMED
        captureInput.visibility = View.VISIBLE
        captureInput.setText(prefill)
        captureInput.setSelection(prefill.length)
        captureInput.requestFocus()
    }

    private fun hideCaptureInput() {
        captureInput.visibility = View.GONE
        captureInput.setText("")
        body.requestFocus()
    }

    private fun appendInviteeEmail(email: String) {
        val normalized = email.trim()
        val invitees = activeInvitees ?: return
        if (normalized.isBlank()) return

        if (invitees.none { it.equals(normalized, ignoreCase = true) }) {
            invitees.add(normalized)
            activeInviteesRenderer?.invoke()
        }
        activeInviteesManualField?.setText("")
    }

    private fun ensureContactPermissionThenLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED) {
            launchInviteeEmailPicker()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERM_REQUEST
            )
        }
    }
    private fun ensureExactAlarmAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun launchInviteeEmailPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Email.CONTENT_URI)
        try {
            pickInviteeEmailLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.toast_no_contact_picker), Toast.LENGTH_SHORT).show()
        }
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Capture: AlertDialog ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬

    private fun showCaptureDialog(
        isAllDay: Boolean,
        prefill: EventFormPrefill = defaultEventPrefill(isAllDay),
        editEventId: Long? = null
    ) {
        val calendars = repo?.getCalendars().orEmpty()
        if (calendars.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_calendars), Toast.LENGTH_LONG).show()
            return
        }

        val dlgTitle = when {
            editEventId != null && isAllDay -> getString(R.string.label_edit_all_day_event)
            editEventId != null -> getString(R.string.label_edit_timed_event)
            isAllDay -> getString(R.string.label_new_all_day_event)
            else -> getString(R.string.label_new_timed_event)
        }
        val fg = appFgColor()
        val bg = appBgColor()
        val subtle = appSubtleTextColor()
        val panel = appPanelColor()
        val stroke = appStrokeColor()

        val content = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 10)
            setBackgroundColor(bg)
        }
        content.addView(
            form,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun labelView(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(fg)
            typeface = uiMediumTypeface
        }

        fun newField(value: String, hint: String = "", multiLine: Boolean = false, lines: Int = 1): EditText {
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                inputType = if (multiLine) {
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                typeface = uiRegularTypeface
                textSize = 12f
                setTextColor(fg)
                setHintTextColor(subtle)
                setPadding(16, 8, 16, 8)
                if (darkModeEnabled()) {
                    backgroundTintList = android.content.res.ColorStateList.valueOf(stroke)
                }
                if (multiLine) {
                    minLines = lines
                    maxLines = lines
                    isVerticalScrollBarEnabled = true
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setHorizontallyScrolling(false)
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                } else {
                    setSingleLine(true)
                }
            }
        }

        fun addField(label: String, value: String, hint: String = "", multiLine: Boolean = false, lines: Int = 1): EditText {
            form.addView(labelView(label))
            val field = newField(value, hint, multiLine, lines)
            form.addView(
                field,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            )
            return field
        }

        fun addTwoColumnFields(
            leftLabel: String,
            leftValue: String,
            leftHint: String,
            rightLabel: String,
            rightValue: String,
            rightHint: String,
            rightIsSpinner: Boolean = false,
            rightSpinner: Spinner? = null
        ): Pair<EditText, View> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val leftBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rightBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            leftBox.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            rightBox.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12
            }

            leftBox.addView(labelView(leftLabel))
            val leftField = newField(leftValue, leftHint)
            leftBox.addView(
                leftField,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            rightBox.addView(labelView(rightLabel))
            val rightView: View = if (rightIsSpinner) {
                rightSpinner ?: View(this)
            } else {
                newField(rightValue, rightHint)
            }
            rightBox.addView(
                rightView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            row.addView(leftBox)
            row.addView(rightBox)
            form.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            )
            return leftField to rightView
        }

        form.addView(labelView(getString(R.string.label_calendar)))
        val calendarSpinner = Spinner(this)
        val calendarLabels = calendars.map { "${it.name} - ${it.accountName}" }
        calendarSpinner.adapter = themedSpinnerAdapter(calendarLabels)
        val targetCalendarId = prefill.calendarId ?: writeCalendarId ?: calendars.first().id
        val selectedCalendarIndex = calendars.indexOfFirst { it.id == targetCalendarId }.let { if (it >= 0) it else 0 }
        calendarSpinner.setSelection(selectedCalendarIndex)
        form.addView(
            calendarSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        )

        val titleField = addField(getString(R.string.label_title), prefill.title, getString(R.string.label_event_title_hint))
        val (startDateField, endDateView) = addTwoColumnFields(
            leftLabel = getString(R.string.label_start_date),
            leftValue = prefill.startDateYYMMDD,
            leftHint = "YYMMDD",
            rightLabel = getString(R.string.label_end_date),
            rightValue = prefill.endDateYYMMDD,
            rightHint = "YYMMDD"
        )
        val endDateField = endDateView as EditText

        listOf(startDateField, endDateField).forEach { field ->
            field.inputType = InputType.TYPE_NULL
            field.isFocusable = false
            field.isFocusableInTouchMode = false
            field.isClickable = true
            field.isCursorVisible = false
            field.keyListener = null
        }

        var endDateAutoFromStart = true
        startDateField.setOnClickListener {
            showDatePickerForField(startDateField) {
                val startText = startDateField.text?.toString().orEmpty()
                val startDate = parseYYMMDD(startText)
                val endDate = parseYYMMDD(endDateField.text?.toString().orEmpty())
                if (endDateAutoFromStart || (startDate != null && endDate != null && endDate.timeInMillis < startDate.timeInMillis)) {
                    endDateField.setText(startText)
                }
            }
        }
        endDateField.setOnClickListener {
            showDatePickerForField(endDateField) {
                endDateAutoFromStart = false
            }
        }

        val startTimeField: EditText?
        val endTimeField: EditText?
        if (!isAllDay) {
            val (sField, eView) = addTwoColumnFields(
                leftLabel = getString(R.string.label_start_time),
                leftValue = prefill.startTimeHHMM,
                leftHint = "HHMM",
                rightLabel = getString(R.string.label_end_time),
                rightValue = prefill.endTimeHHMM,
                rightHint = "HHMM"
            )
            startTimeField = sField
            endTimeField = eView as EditText
            var endTimeAutoFromStart = true

            listOf(startTimeField, endTimeField).forEach { field ->
                field.inputType = InputType.TYPE_NULL
                field.isFocusable = false
                field.isFocusableInTouchMode = false
                field.isClickable = true
                field.isCursorVisible = false
                field.keyListener = null
            }

            startTimeField.setOnClickListener {
                val (hour, minute) = parseHHMMPartsOrDefault(startTimeField.text?.toString().orEmpty())
                TimePickerDialog(
                    this,
                    { _, pickedHour, pickedMinute ->
                        startTimeField.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
                        if (endTimeAutoFromStart) {
                            setFieldFromFieldPlusMinutes(startTimeField, endTimeField, 60)
                        }
                    },
                    hour,
                    minute,
                    true
                ).show()
            }

            endTimeField.setOnClickListener {
                val (hour, minute) = parseHHMMPartsOrDefault(endTimeField.text?.toString().orEmpty())
                TimePickerDialog(
                    this,
                    { _, pickedHour, pickedMinute ->
                        endTimeField.setText(String.format(Locale.US, "%02d%02d", pickedHour, pickedMinute))
                        endTimeAutoFromStart = false
                    },
                    hour,
                    minute,
                    true
                ).show()
            }

            val quickRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            fun smallBtn(label: String, onClick: () -> Unit): android.widget.Button {
                return android.widget.Button(this@MainActivity).apply {
                    text = label
                    textSize = 11f                    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã‹Å“ frÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¥n 9f
                    typeface = uiRegularTypeface

                    minWidth = 0
                    minimumWidth = 0

                    minHeight = dpInt(36f)           // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã‹Å“ viktigaste: bÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤ttre touch-yta
                    minimumHeight = dpInt(36f)

                    setPadding(
                        dpInt(6f),
                        dpInt(6f),                   // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÂ¢Ã¢â€šÂ¬Ã‹Å“ lite mer vertikal padding
                        dpInt(6f),
                        dpInt(6f)
                    )

                    isAllCaps = false
                    setOnClickListener { onClick() }
                }
            }

            fun addQuickButton(label: String, onClick: () -> Unit) {
                quickRow.addView(
                    smallBtn(label, onClick),
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (quickRow.childCount > 0) marginStart = dpInt(4f)
                    }
                )
            }

            addQuickButton("=start") {
                endTimeField.setText(startTimeField.text?.toString().orEmpty())
                endTimeAutoFromStart = false
            }

            addQuickButton("+15m") {
                setEndFromStart(startTimeField, endTimeField, 15)
                endTimeAutoFromStart = false
            }

            addQuickButton("+30m") {
                setEndFromStart(startTimeField, endTimeField, 30)
                endTimeAutoFromStart = false
            }

            addQuickButton("+45m") {
                setEndFromStart(startTimeField, endTimeField, 45)
                endTimeAutoFromStart = false
            }

            addQuickButton("+1h") {
                setEndFromStart(startTimeField, endTimeField, 60)
                endTimeAutoFromStart = false
            }

            form.addView(
                quickRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(6f) }
            )
        } else {
            startTimeField = null
            endTimeField = null
        }




        val locationField = addField(getString(R.string.label_location), prefill.location, getString(R.string.label_optional))

        val repeatChoices = repeatOptions()
        val repeatSpinner = Spinner(this).apply {
            adapter = themedSpinnerAdapter(repeatChoices)
            val repeatIndex = repeatChoices.indexOf(prefill.repeatLabel).let { if (it >= 0) it else 0 }
            setSelection(repeatIndex)
        }

        val reminderChoices = reminderOptions(isAllDay)
        val reminderSpinner = Spinner(this).apply {
            adapter = themedSpinnerAdapter(reminderChoices)
            val reminderIndex = reminderChoices.indexOf(prefill.reminderLabel).let { if (it >= 0) it else 0 }
            setSelection(reminderIndex)
        }

        val repeatReminderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val repeatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val reminderBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeatBox.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        reminderBox.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12
        }
        repeatBox.addView(labelView(getString(R.string.label_repeat)))
        repeatBox.addView(
            repeatSpinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        reminderBox.addView(labelView(getString(R.string.label_reminder)))
        reminderBox.addView(
            reminderSpinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        repeatReminderRow.addView(repeatBox)
        repeatReminderRow.addView(reminderBox)
        form.addView(
            repeatReminderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        )

        val descriptionField = addField(getString(R.string.label_description), prefill.description, getString(R.string.label_optional), multiLine = true, lines = 2)

        form.addView(labelView(getString(R.string.label_invitees)))

        val invitees = prefill.attendeeEmails
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, email ->
                if (acc.none { it.equals(email, ignoreCase = true) }) acc.add(email)
                acc
            }

        val inviteesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        form.addView(
            inviteesContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        )

        val inviteManualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val inviteManualField = newField("", getString(R.string.label_email_hint))
        inviteManualRow.addView(
            inviteManualField,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val addTypedInviteeButton = android.widget.Button(this).apply {
            text = getString(R.string.label_add)
            typeface = uiRegularTypeface
            setOnClickListener {
                appendInviteeEmail(inviteManualField.text?.toString().orEmpty())
            }
        }
        inviteManualRow.addView(
            addTypedInviteeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 12 }
        )

        val addInviteeButton = android.widget.Button(this).apply {
            text = "+"
            typeface = uiMediumTypeface
            textSize = 18f
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 8, 24, 8)
            setOnClickListener { ensureContactPermissionThenLaunch() }
        }
        inviteManualRow.addView(
            addInviteeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 12 }
        )
        form.addView(
            inviteManualRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        )

        fun renderInviteeChips() {
            inviteesContainer.removeAllViews()

            if (invitees.isEmpty()) {
                inviteesContainer.addView(
                    TextView(this).apply {
                        text = getString(R.string.label_no_invitees)
                        textSize = 12f
                        setTextColor(subtle)
                        typeface = uiRegularTypeface
                        setPadding(12, 12, 12, 12)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                return
            }

            invitees.forEach { email ->
                val chipRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 8, 12, 8)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(panel)
                        setStroke(dpInt(1f), stroke)
                        cornerRadius = dp(8f)
                    }
                }

                val chipLabel = TextView(this).apply {
                    text = email
                    textSize = 12f
                    setTextColor(fg)
                    typeface = uiRegularTypeface
                }
                chipRow.addView(
                    chipLabel,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )

                val removeButton = android.widget.Button(this).apply {
                    text = getString(R.string.label_remove)
                    typeface = uiRegularTypeface
                    textSize = 16f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(16, 0, 16, 0)
                    setOnClickListener {
                        invitees.removeAll { it.equals(email, ignoreCase = true) }
                        renderInviteeChips()
                    }
                }
                chipRow.addView(
                    removeButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = 8 }
                )

                inviteesContainer.addView(
                    chipRow,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                )
            }
        }

        activeInvitees = invitees
        activeInviteesRenderer = { renderInviteeChips() }
        activeInviteesManualField = inviteManualField
        renderInviteeChips()

        val dialogTitleView = TextView(this).apply {
            text = dlgTitle
            textSize = 17f
            setTextColor(fg)
            typeface = uiMediumTypeface
            setPadding(dpInt(24f), dpInt(18f), dpInt(24f), dpInt(8f))
            setBackgroundColor(bg)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setCustomTitle(dialogTitleView)
            .setView(content)
            .setPositiveButton(getString(R.string.label_save), null)
            .setNegativeButton(getString(R.string.label_cancel), null)
            .create()

        dialog.setOnDismissListener {
            activeInvitees = null
            activeInviteesRenderer = null
            activeInviteesManualField = null
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(bg)
                    setStroke(dpInt(1f), stroke)
                }
            )
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(fg)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(fg)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedCal = calendars.getOrNull(calendarSpinner.selectedItemPosition) ?: calendars.first()
                val err = saveEventForm(
                    EventFormData(
                        calendarId = selectedCal.id,
                        title = titleField.text.toString(),
                        startDateYYMMDD = startDateField.text.toString().trim(),
                        endDateYYMMDD = endDateField.text.toString().trim(),
                        startTimeHHMM = startTimeField?.text?.toString()?.trim().orEmpty(),
                        endTimeHHMM = endTimeField?.text?.toString()?.trim().orEmpty(),
                        location = locationField.text.toString(),
                        description = descriptionField.text.toString(),
                        rrule = repeatLabelToRRule(repeatSpinner.selectedItem?.toString().orEmpty()),
                        reminderMinutes = reminderLabelToMinutes(reminderSpinner.selectedItem?.toString().orEmpty()),
                        attendeeEmails = invitees.toList()
                    ),
                    isAllDay = isAllDay,
                    editEventId = editEventId
                )
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                } else {
                    selectedEvent = null
                    returnToPreviousScreen("save event")
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
        titleField.requestFocus()
        titleField.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(titleField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ Settings view ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚ÂÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬


    private fun renderSettingsView(out: StringBuilder) {
        fun statusLabel(ok: Boolean): String =
            if (ok) getString(R.string.label_ok) else getString(R.string.label_missing)
        fun mark(on: Boolean, symbol: String): String = if (on) symbol else " "
        fun add(lines: MutableList<String>, line: String = "") { lines.add(line) }

        val calendarOk = hasCalendarPermissions()
        val contactsOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsOk =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

        val versionLabel =
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"

        val lines = mutableListOf<String>()
        add(lines)
        add(lines, "${getString(R.string.app_name)} $versionLabel")
        add(lines)
        add(lines, getString(R.string.label_permissions_section))
        add(lines, getString(R.string.label_permissions_calendar_status, statusLabel(calendarOk)))
        add(lines, getString(R.string.label_permissions_contacts_status, statusLabel(contactsOk)))
        add(lines, getString(R.string.label_permissions_notifications_status, statusLabel(notificationsOk)))
        add(lines, getString(R.string.label_request_calendar_permission))
        add(lines)

        val cals = repo?.getCalendars() ?: emptyList()
        add(lines, getString(R.string.label_calendars_section))
        if (cals.isEmpty()) {
            add(lines, "  ${getString(R.string.toast_no_calendars)}")
            add(lines, getString(R.string.label_ensure_google_calendar_synced))
            add(lines)
        } else {
            add(lines, getString(R.string.label_calendar_table_header))
            cals.forEachIndexed { i, cal ->
                val readMark = mark(cal.id in enabledCalendarIds, "ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¢")
                val writeMark = mark(cal.id == writeCalendarId, "ÃƒÆ’Ã‚Â¢Ãƒâ€¹Ã…â€œÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¦")
                val idx = (i + 1).toString().padStart(2)
                val name = cal.name.ifBlank { getString(R.string.label_unnamed_calendar) }
                add(lines, "  $idx  $readMark  $writeMark  $name")
                add(lines, "           ${cal.accountName}")
            }
            add(lines)
        }

        add(lines, getString(R.string.label_alarm_section))
        add(lines, getString(R.string.label_alarm_enabled_status, if (alarmEngineEnabled()) getString(R.string.label_yes) else getString(R.string.label_no)))
        add(lines, getString(R.string.label_alarm_fallback_status, alarmFallbackLabel()))
        add(lines, getString(R.string.label_week_start_status, weekStartLabel()))
        add(lines)

        add(lines, getString(R.string.label_controls_section))
        add(lines, getString(R.string.label_controls_toggle_read))
        add(lines, getString(R.string.label_controls_set_write))
        add(lines, getString(R.string.label_controls_toggle_alarm))
        add(lines, getString(R.string.label_controls_cycle_fallback))
        add(lines, getString(R.string.label_controls_cycle_week_start))
        add(lines, getString(R.string.label_controls_page))
        add(lines, getString(R.string.label_controls_request_permission))
        add(lines, getString(R.string.label_controls_week))
        add(lines, getString(R.string.label_controls_month))
        add(lines, getString(R.string.label_controls_today))

        val lineHeight = kotlin.math.ceil(body.paint.fontMetrics.run { bottom - top }).toInt().coerceAtLeast(16)
        val fallbackHeight = when {
            body.height > 0 -> body.height
            bodyContainer.height > 0 -> bodyContainer.height
            else -> resources.displayMetrics.heightPixels
        }
        val availablePx = (fallbackHeight - body.paddingTop - body.paddingBottom).coerceAtLeast(lineHeight * 8)
        val linesPerPage = (availablePx / lineHeight).coerceAtLeast(8)

        val totalPages = ((lines.size - 1) / linesPerPage + 1).coerceAtLeast(1)
        settingsPageOffset = settingsPageOffset.coerceIn(0, totalPages - 1)
        val start = settingsPageOffset * linesPerPage
        val end = min(start + linesPerPage, lines.size)

        lines.subList(start, end).forEach { out.appendLine(it) }

        if (totalPages > 1) {
            out.appendLine()
            out.appendLine(getString(R.string.label_page_status, settingsPageOffset + 1, totalPages))
        }
    }

    private fun ensureSettingsPanel() {
        if (settingsScrollView != null && settingsContent != null) return

        settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(12f), dpInt(12f), dpInt(12f), dpInt(24f))
        }

        settingsScrollView = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            setBackgroundColor(appBgColor())
            addView(
                settingsContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        bodyContainer.addView(
            settingsScrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun scrollSettingsPage(direction: Int) {
        val scroll = settingsScrollView ?: return
        val step = (bodyContainer.height * 0.85f).toInt().coerceAtLeast(dpInt(80f))
        scroll.post {
            scroll.scrollBy(0, step * direction)
        }
    }

    private fun renderSettingsPanel() {
        ensureSettingsPanel()
        val content = settingsContent ?: return
        content.removeAllViews()
        val fg = appFgColor()
        val bg = appBgColor()
        val subtle = appSubtleTextColor()
        val panel = appPanelColor()
        val stroke = appStrokeColor()
        settingsScrollView?.setBackgroundColor(bg)
        content.setBackgroundColor(bg)

        val calendarOk = hasCalendarPermissions()
        val notificationOk =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        val exactAlarmOk =
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        val calendars = if (calendarOk) repo?.getCalendars().orEmpty() else emptyList()
        val versionLabel =
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"

        fun sectionTitle(text: String) {
            content.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(fg)
                    typeface = uiMediumTypeface
                    setPadding(0, dpInt(8f), 0, dpInt(6f))
                }
            )
        }

        fun bodyText(text: String) {
            content.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = 12f
                    setTextColor(subtle)
                    typeface = uiRegularTypeface
                    setPadding(dpInt(4f), 0, dpInt(4f), dpInt(8f))
                }
            )
        }

        fun addRow(title: String, subtitle: String? = null, control: View? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpInt(10f), dpInt(10f), dpInt(10f), dpInt(10f))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(panel)
                    setStroke(dpInt(1f), stroke)
                    cornerRadius = dp(8f)
                }
            }

            val textBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            textBox.addView(
                TextView(this).apply {
                    text = title
                    textSize = 13f
                    setTextColor(fg)
                    typeface = uiMediumTypeface
                }
            )
            if (!subtitle.isNullOrBlank()) {
                textBox.addView(
                    TextView(this).apply {
                        text = subtitle
                        textSize = 11f
                        setTextColor(subtle)
                        typeface = uiRegularTypeface
                    }
                )
            }

            row.addView(
                textBox,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            if (control != null) {
                row.addView(
                    control,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dpInt(8f) }
                )
            }

            content.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpInt(8f) }
            )
        }

        sectionTitle(getString(R.string.label_version))
        addRow(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.label_version_value, versionLabel)
        )
        addRow(
            title = getString(R.string.label_dark_mode),
            subtitle = getString(R.string.label_dark_mode_summary),
            control = SwitchCompat(this).apply {
                isChecked = darkModeEnabled()
                setTextColor(fg)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked == darkModeEnabled()) return@setOnCheckedChangeListener
                    setDarkModeEnabled(isChecked)
                    applyAppThemeChrome()
                    monthCanvas?.invalidate()
                    weekCanvas?.invalidate()
                    dayCanvas?.invalidate()
                    render()
                    if (isChecked && darkModeEpaperRefreshEnabled()) flashEpaperRefresh()
                }
            }
        )
        if (darkModeEnabled()) {
            addRow(
                title = getString(R.string.label_epaper_refresh),
                subtitle = getString(R.string.label_epaper_refresh_summary),
                control = SwitchCompat(this).apply {
                    isChecked = darkModeEpaperRefreshEnabled()
                    setTextColor(fg)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked == darkModeEpaperRefreshEnabled()) return@setOnCheckedChangeListener
                        setDarkModeEpaperRefreshEnabled(isChecked)
                        render()
                        if (isChecked) flashEpaperRefresh()
                    }
                }
            )
        }

        sectionTitle(getString(R.string.label_permissions_section))
        addRow(
            title = getString(R.string.label_calendar_access),
            subtitle = if (calendarOk) getString(R.string.label_granted) else getString(R.string.label_required),
            control = Button(this).apply {
                text = if (calendarOk) getString(R.string.label_granted) else getString(R.string.label_grant)
                typeface = uiRegularTypeface
                setTextColor(fg)
                isEnabled = !calendarOk
                setOnClickListener { requestCalendarPermissions() }
            }
        )
        addRow(
            title = getString(R.string.label_notification_access),
            subtitle = if (notificationOk) getString(R.string.label_granted) else getString(R.string.label_required),
            control = Button(this).apply {
                text = if (notificationOk) getString(R.string.label_granted) else getString(R.string.label_grant)
                typeface = uiRegularTypeface
                setTextColor(fg)
                isEnabled = !notificationOk
                setOnClickListener { ensureNotificationPermission() }
            }
        )
        addRow(
            title = getString(R.string.label_exact_alarm_access),
            subtitle = if (exactAlarmOk) getString(R.string.label_granted) else getString(R.string.label_required),
            control = Button(this).apply {
                text = if (exactAlarmOk) getString(R.string.label_granted) else getString(R.string.label_open)
                typeface = uiRegularTypeface
                setTextColor(fg)
                isEnabled = !exactAlarmOk
                setOnClickListener { ensureExactAlarmAccess() }
            }
        )

        sectionTitle(getString(R.string.label_calendars_section))
        if (calendars.isEmpty()) {
            bodyText(
                if (calendarOk) getString(R.string.toast_no_calendars)
                else getString(R.string.label_calendar_access_required)
            )
        } else {
            bodyText(getString(R.string.label_settings_calendar_hint))
            calendars.forEach { cal ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpInt(10f), dpInt(10f), dpInt(10f), dpInt(10f))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(panel)
                        setStroke(dpInt(1f), stroke)
                        cornerRadius = dp(8f)
                    }
                }

                val textBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                textBox.addView(
                    TextView(this).apply {
                        text = cal.name.ifBlank { getString(R.string.label_unnamed_calendar) }
                        textSize = 13f
                        setTextColor(fg)
                        typeface = uiMediumTypeface
                    }
                )
                textBox.addView(
                    TextView(this).apply {
                        text = cal.accountName
                        textSize = 11f
                        setTextColor(subtle)
                        typeface = uiRegularTypeface
                    }
                )
                row.addView(
                    textBox,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )

                val controls = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val readSwitch = SwitchCompat(this).apply {
                    text = getString(R.string.label_read)
                    typeface = uiRegularTypeface
                    setTextColor(fg)
                    isChecked = cal.id in enabledCalendarIds
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            enabledCalendarIds.add(cal.id)
                            prefs.edit().putBoolean("read_calendar_${cal.id}", true).apply()
                        } else {
                            enabledCalendarIds.remove(cal.id)
                            prefs.edit().putBoolean("read_calendar_${cal.id}", false).apply()
                            if (writeCalendarId == cal.id) {
                                setWriteCalendar(enabledCalendarIds.firstOrNull())
                            }
                        }
                        reloadEvents()
                        AlarmScheduler.scheduleNext(this@MainActivity)
                        render()
                    }
                }

                val writeRadio = RadioButton(this).apply {
                    text = getString(R.string.label_write)
                    typeface = uiRegularTypeface
                    setTextColor(fg)
                    isChecked = cal.id == writeCalendarId
                    setOnCheckedChangeListener { _, isChecked ->
                        if (!isChecked) return@setOnCheckedChangeListener
                        if (cal.id !in enabledCalendarIds) {
                            enabledCalendarIds.add(cal.id)
                            prefs.edit().putBoolean("read_calendar_${cal.id}", true).apply()
                        }
                        setWriteCalendar(cal.id)
                        render()
                    }
                }

                controls.addView(readSwitch)
                controls.addView(writeRadio)
                row.addView(
                    controls,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dpInt(8f) }
                )

                content.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpInt(8f) }
                )
            }
        }

        sectionTitle(getString(R.string.label_alarm_section))
        addRow(
            title = getString(R.string.label_alarm_engine),
            subtitle = getString(
                R.string.label_alarm_engine_summary,
                if (alarmEngineEnabled()) getString(R.string.label_yes) else getString(R.string.label_no)
            ),
            control = SwitchCompat(this).apply {
                isChecked = alarmEngineEnabled()
                setOnCheckedChangeListener { _, isChecked ->
                    setAlarmEngineEnabled(isChecked)
                    if (isChecked) {
                        if (!hasCalendarPermissions()) requestCalendarPermissions()
                        ensureNotificationPermission()
                        ensureExactAlarmAccess()
                        AlarmScheduler.scheduleNext(this@MainActivity)
                    } else {
                        AlarmScheduler.cancelAll(this@MainActivity)
                    }
                    render()
                }
            }
        )

        val fallbackValues = alarmFallbackOptions.toList()
        val fallbackLabels = fallbackValues.map { alarmFallbackOptionLabel(it) }
        val fallbackSpinner = Spinner(this).apply {
            adapter = themedSpinnerAdapter(fallbackLabels)
            setSelection(fallbackValues.indexOf(alarmFallbackMinutes()).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = fallbackValues.getOrNull(position) ?: return
                    if (selected == alarmFallbackMinutes()) return
                    setAlarmFallbackMinutes(selected)
                    AlarmScheduler.scheduleNext(this@MainActivity)
                    render()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addRow(
            title = getString(R.string.label_fallback_time),
            subtitle = getString(R.string.label_fallback_summary),
            control = fallbackSpinner
        )

        sectionTitle(getString(R.string.label_week_start))
        val currentWeekStart = configuredWeekStartPreference()
        val weekStartGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        listOf(
            0 to getString(R.string.label_week_start_system_default, localizedWeekdayName(localeDefaultFirstDayOfWeek())),
            Calendar.MONDAY to localizedWeekdayName(Calendar.MONDAY),
            Calendar.SUNDAY to localizedWeekdayName(Calendar.SUNDAY)
        ).forEachIndexed { index, (value, label) ->
            val radioId = View.generateViewId()
            weekStartGroup.addView(
                RadioButton(this).apply {
                    id = radioId
                    text = label
                    typeface = uiRegularTypeface
                    setTextColor(fg)
                    isChecked = currentWeekStart == value
                    setOnCheckedChangeListener { _, isChecked ->
                        if (!isChecked) return@setOnCheckedChangeListener
                        if (configuredWeekStartPreference() == value) return@setOnCheckedChangeListener
                        setWeekStartPreference(value)
                        reloadEvents()
                        pageOffset = 0
                        render()
                    }
                },
                if (index == 0) {
                    RadioGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                } else {
                    RadioGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpInt(2f) }
                }
            )
        }
        addRow(
            title = getString(R.string.label_week_start),
            subtitle = getString(R.string.label_week_start_gui_summary),
            control = weekStartGroup
        )
    }


    private fun estimateMonospaceColumns(tv: TextView): Int {
        val charW = tv.paint.measureText("M").coerceAtLeast(1f)
        val usable = (tv.width - tv.paddingLeft - tv.paddingRight).coerceAtLeast(0)
        return (usable / charW).toInt().coerceIn(20, 200)
    }

    private fun center(text: String, width: Int): String {
        if (width <= 0) return ""
        if (text.length >= width) return text.take(width)
        val pad = width - text.length
        val left = pad / 2
        val right = pad - left
        return " ".repeat(left) + text + " ".repeat(right)
    }

    private fun padToSize(list: List<String>, n: Int): List<String> {
        if (n <= 0) return emptyList()
        if (list.size >= n) return list.take(n)
        return list + List(n - list.size) { "" }
    }

    private fun wrap(text: String, width: Int, maxLines: Int): List<String> {
        if (width <= 0 || maxLines <= 0) return emptyList()
        val t = text.trim()
        if (t.isEmpty()) return emptyList()

        val words = t.split(Regex("""\s+"""))
        val lines = mutableListOf<String>()
        var cur = ""

        fun flush() {
            if (cur.isNotEmpty()) lines.add(cur)
            cur = ""
        }

        for (w0 in words) {
            var w = w0
            while (w.length > width) {
                val part = w.take(width)
                if (cur.isNotEmpty()) flush()
                lines.add(part)
                w = w.drop(width)
                if (lines.size >= maxLines) return lines.take(maxLines)
            }

            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (candidate.length <= width) {
                cur = candidate
            } else {
                flush()
                cur = w
                if (lines.size >= maxLines) break
            }
            if (lines.size >= maxLines) break
        }

        flush()
        return lines.take(maxLines)
    }

    private fun appendRight(base: String, right: String, width: Int): String {
        if (width <= 0) return ""
        val b = base.trimEnd()
        val r = right.trim()
        if (r.length >= width) return r.take(width)
        val space = width - r.length
        val left = if (b.length >= space) b.take(space - 1) + " " else b.padEnd(space)
        return left + r
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun dpInt(v: Float): Int = dp(v).toInt()

    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚Â


    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚Â
    // WeekCanvasView ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â graphical week grid with spanning event blocks
    // ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚Â
    class WeekCanvasView(context: android.content.Context) : View(context) {

        private data class DayEventLayout(
            val ev: CalEvent,
            val lane: Int,
            val laneCount: Int
        )

        var onEventClick: ((CalendarRepository.Event) -> Unit)? = null
        var onDayTap: ((Calendar) -> Unit)? = null

        private var selectedDay: Calendar = Calendar.getInstance()
        private var byDay: Map<Int, List<CalEvent>> = emptyMap()
        // Raw repo events per day index ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â used to look up the real Event on tap
        private var rawEventsByDay: Map<Int, List<CalendarRepository.Event>> = emptyMap()

        private var pageOffset: Int = 0
        private var scrollPx: Float = 0f
        private var maxScrollPx: Float = 0f
        private var showFooter: Boolean = true
        private var darkMode: Boolean = false

        // Hit areas for timed events ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â rebuilt every onDraw
        private data class EventHitArea(val event: CalendarRepository.Event, val rect: RectF)
        private data class EventCardLayout(
            val event: CalendarRepository.Event,
            val titleLines: List<String>,
            val contentLines: List<String>,
            val cardHeight: Float
        )
        private val eventHitAreas = mutableListOf<EventHitArea>()

        private val today: Calendar = Calendar.getInstance()

        // Visuals
        private val fontSp = 10f
        private val padDp = 4f
        private val headerHDp = 34f
        private val timeColWDp = 46f
        private val gapHDp = 12f
        private val hourBlockHDp = 42f
        private val laneGapDp = 4f
        private val minEventHeightDp = 22f
        private val footerHDp = 18f

        private val pBg = Paint().apply { color = Color.WHITE }
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = dp(0.6f) }
        private val pWeekend = Paint().apply {
            color = Color.rgb(251, 251, 251)   // mycket ljus grÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¥
        }
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sp(fontSp)
            typeface = Typeface.MONOSPACE
        }
        private val pHeaderText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isSubpixelText = true
            textAlign = Paint.Align.CENTER
        }
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val pEvFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val pEvStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(1f) }

        private fun applyPalette() {
            val bg = if (darkMode) Color.BLACK else Color.WHITE
            val fg = if (darkMode) Color.WHITE else Color.BLACK
            val line = if (darkMode) Color.rgb(95, 95, 95) else Color.BLACK
            val weekend = if (darkMode) Color.rgb(20, 20, 20) else Color.rgb(251, 251, 251)
            val card = if (darkMode) Color.rgb(28, 28, 28) else Color.WHITE
            pBg.color = bg
            pLine.color = line
            pWeekend.color = weekend
            pText.color = fg
            pHeaderText.color = fg
            pDot.color = fg
            pEvFill.color = card
            pEvStroke.color = fg
        }

        // Layout model
        private var days: List<Calendar> = emptyList()
        private var slots: List<WeekSlot> = emptyList()
        private var minHour: Int = 8
        private var maxHourExclusive: Int = 21
        private var firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek

        private val hourTopMap: MutableMap<Int, Float> = mutableMapOf()
        private val hourHeightMap: MutableMap<Int, Float> = mutableMapOf()
        private var contentStartY: Float = 0f
        private var contentEndY: Float = 0f

        fun update(sel: Calendar, cachedWeek: Map<Int, List<CalendarRepository.Event>>, repoOk: Boolean, pageOffset: Int, showFooter: Boolean, firstDayOfWeek: Int, darkMode: Boolean) {
            selectedDay = sel
            this.firstDayOfWeek = firstDayOfWeek
            this.darkMode = darkMode
            applyPalette()
            days = buildWeekDays(sel, firstDayOfWeek)
            rawEventsByDay = cachedWeek

            byDay = (0..6).associateWith { idx ->
                cachedWeek[idx].orEmpty().map { ev ->
                    CalEvent(
                        dayIndex = idx,
                        title = ev.title,
                        isAllDay = ev.isAllDay,
                        startMin = ev.startMin,
                        endMin = ev.endMin,
                        startDayEpoch = ev.startDayEpoch,
                        endDayEpoch = ev.endDayEpoch
                    )
                }
            }

            val timedByDay = (0..6).map { di -> byDay[di].orEmpty().filter { !it.isAllDay } }
            val win = computeHourWindow(timedByDay)
            minHour = win.first
            maxHourExclusive = win.second
            slots = buildSlots(timedByDay, minHour, maxHourExclusive)

            this.pageOffset = pageOffset
            this.showFooter = showFooter
            post { recomputeScroll() }

            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recomputeScroll()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (days.isEmpty()) return
            eventHitAreas.clear()

            val W = width.toFloat()
            val H = height.toFloat()

            val pad = dp(padDp)
            val headerH = dp(headerHDp)
            val timeColW = dp(timeColWDp)
            val gapH = dp(gapHDp)
            val defaultHourBlockH = dp(hourBlockHDp)
            val laneGap = dp(laneGapDp)
            val minEventH = dp(minEventHeightDp)
            val footerH = if (showFooter) dp(footerHDp) else 0f
            val contentBottom = H - footerH

            val dayColW = (W - timeColW) / 7f

            canvas.drawRect(0f, 0f, W, H, pBg)

            val fm = pText.fontMetrics
            val lineH = (fm.bottom - fm.top)
            val textBaselineAdjust = -fm.top

            // Header
            val weekNum = (days[0].clone() as Calendar).apply {
                this.firstDayOfWeek = this@WeekCanvasView.firstDayOfWeek
                minimalDaysInFirstWeek = Calendar.getInstance(context.resources.configuration.locales[0] ?: Locale.getDefault()).minimalDaysInFirstWeek
            }.get(Calendar.WEEK_OF_YEAR)
            val currentLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val monthLabel = SimpleDateFormat("MMM", currentLocale).format(selectedDay.time)
            pText.textAlign = Paint.Align.LEFT
            //canvas.drawText("W$weekNum $monthLabel", pad, headerH - pad, pText)

            for (i in 0..6) {
                val cal = days[i]
                val dow = SimpleDateFormat("EEE", currentLocale)
                    .format(cal.time)
                    .replace(".", "")
                    .trim()
                    .take(1)
                    .uppercase(currentLocale)
                val dn = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                val label = "$dow$dn"

                val x0 = timeColW + i * dayColW
                val x1 = x0 + dayColW

                val dowe = days[i].get(Calendar.DAY_OF_WEEK)
                if (dowe == Calendar.SATURDAY || dowe == Calendar.SUNDAY) {
                    canvas.drawRect(
                        x0,
                        headerH,
                        x1,
                        height.toFloat(),
                        pWeekend
                    )
                }

                canvas.drawText(label, (x0 + x1) / 2f, headerH - pad, pHeaderText)

                if (sameDay(cal, selectedDay)) {
                    canvas.drawCircle((x0 + x1) / 2f, headerH - pad + dp(6f), dp(2.2f), pDot)
                }
                if (sameDay(cal, today)) {
                    val uy = headerH - dp(4f)
                    canvas.drawLine(x0 + dp(8f), uy, x1 - dp(8f), uy, pLine)
                }
            }

            // Header lines
            canvas.drawLine(0f, headerH, W, headerH, pLine)
            canvas.drawLine(timeColW, 0f, timeColW, contentBottom, pLine)
            for (i in 0..7) {
                val x = timeColW + i * dayColW
                canvas.drawLine(x, headerH, x, contentBottom, pLine)
            }

            canvas.save()
            canvas.clipRect(0f, headerH, W, contentBottom)
            canvas.translate(0f, -scrollPx)

            val timedByDay = (0..6).map { di ->
                byDay[di].orEmpty().filter { !it.isAllDay }.sortedWith(
                    compareBy<CalEvent> { it.startMin }.thenBy { it.endMin }.thenBy { it.title.lowercase(Locale.getDefault()) }
                )
            }
            val allDayByDay = (0..6).map { di -> byDay[di].orEmpty().filter { it.isAllDay } }

            // All-day area
            val allDayRows = 3
            var y = headerH + dp(4f)
            for (r in 0 until allDayRows) {
                for (d in 0..6) {
                    val x0 = timeColW + d * dayColW
                    val x1 = x0 + dayColW
                    val ev = allDayByDay[d].getOrNull(r)
                    if (ev != null) {
                        val rect = RectF(x0 + pad, y, x1 - pad, y + lineH + dp(6f))
                        canvas.drawRoundRect(rect, dp(5f), dp(5f), pEvFill)
                        canvas.drawRoundRect(rect, dp(5f), dp(5f), pEvStroke)
                        drawLineClipped(canvas, ev.title, rect.left + pad, rect.top + textBaselineAdjust, rect.width() - 2*pad)
                    }
                }
                y += lineH + dp(8f)
            }

            canvas.drawLine(0f, y, W, y, pLine)
            y += dp(6f)

            contentStartY = y
            hourTopMap.clear()
            hourHeightMap.clear()

            // Precompute tops for each active hour slot using dynamic per-hour heights.
            for (slot in slots) {
                when (slot) {
                    is WeekSlot.Gap -> {
                        y += gapH
                        canvas.drawLine(0f, y, W, y, pLine)
                        y += dp(6f)
                    }
                    is WeekSlot.Active -> {
                        val hPx = estimateHourHeightPx(
                            hour = slot.hour,
                            timedByDay = timedByDay,
                            colWidth = dayColW,
                            dayPad = pad,
                            lineH = lineH
                        ).coerceAtLeast(defaultHourBlockH)

                        hourTopMap[slot.hour] = y
                        hourHeightMap[slot.hour] = hPx
                        hourTopMap[slot.hour + 1] = y + hPx
                        y += hPx
                        canvas.drawLine(0f, y, W, y, pLine)
                        y += dp(6f)
                    }
                }
            }
            contentEndY = y

            // Draw time labels and gap labels in a second pass
            y = contentStartY
            for (slot in slots) {
                when (slot) {
                    is WeekSlot.Gap -> {
                        pText.textAlign = Paint.Align.LEFT
                        val label = if (slot.from == slot.to) {
                            formatMinute(slot.from * 60)
                        } else {
                            "${slot.from.toString().padStart(2,'0')}-${slot.to.toString().padStart(2,'0')}"
                        }
                        canvas.drawText(label, pad, y + textBaselineAdjust, pText)
                        y += gapH
                        y += dp(6f)
                    }
                    is WeekSlot.Active -> {
                        pText.textAlign = Paint.Align.LEFT
                        canvas.drawText(formatMinute(slot.hour * 60), pad, y + textBaselineAdjust, pText)
                        y += (hourHeightMap[slot.hour] ?: defaultHourBlockH)
                        y += dp(6f)
                    }
                }
            }

            // Draw stacked full-width event blocks (information-first model)
            for (d in 0..6) {
                val dayEvents = timedByDay[d]
                val x0 = timeColW + d * dayColW
                val x1 = x0 + dayColW
                val left = x0 + pad
                val right = x1 - pad

                val sortedEvents = dayEvents.sortedWith(compareBy<CalEvent> { it.startMin }.thenBy { it.endMin })
                val drawnHeights = mutableMapOf<CalEvent, Float>()

                for (ev in sortedEvents) {
                    val stackOffset = computeStackOffsetPx(
                        ev = ev,
                        dayEvents = sortedEvents,
                        drawnHeights = drawnHeights,
                        colWidth = dayColW,
                        dayPad = pad,
                        lineH = lineH
                    )

                    val enforcedMinHeight = estimateEventOuterHeightPx(
                        ev = ev,
                        colWidth = dayColW,
                        dayPad = pad,
                        lineH = lineH
                    ).coerceAtLeast(minEventH)

                    val startHour = (ev.startMin / 60).coerceIn(minHour, (maxHourExclusive - 1).coerceAtLeast(minHour))
                    val hasConcurrent = sortedEvents.any { other ->
                        other != ev &&
                                other.startMin < ev.endMin &&
                                other.endMin > ev.startMin &&
                                (other.startMin / 60) == startHour
                    }

                    val top: Float
                    val bottom: Float
                    if (hasConcurrent) {
                        val slotTop = hourTopMap[startHour] ?: yForMinute(ev.startMin)
                        top = slotTop + stackOffset
                        bottom = top + enforcedMinHeight
                    } else {
                        var b = yForMinute(ev.endMin)
                        if (ev.endMin % 60 == 0) b -= dp(2f)
                        val t = yForMinute(ev.startMin)
                        top = t
                        bottom = if (b - t < enforcedMinHeight) t + enforcedMinHeight else b
                    }

                    val rect = RectF(left, top + dp(2f), right, bottom - dp(4f))
                    drawnHeights[ev] = rect.height()

                    val innerLeft = rect.left + pad
                    val innerRight = rect.right - pad
                    val innerTop = rect.top + pad
                    val innerBottom = rect.bottom - pad
                    val textWidthPx = (innerRight - innerLeft).coerceAtLeast(dp(8f))
                    val maxChars = (textWidthPx / pText.measureText("M").coerceAtLeast(1f)).toInt().coerceAtLeast(1)

                    val showStart = ev.startMin % 60 != 0
                    val showEnd = ev.endMin % 60 != 0
                    val rowStep = lineH + dp(2f)
                    val minRowsNeeded = 1 + (if (showStart) 1 else 0) + (if (showEnd) 1 else 0)
                    val totalRows = ((innerBottom - innerTop) / rowStep).toInt().coerceAtLeast(minRowsNeeded)
                    val reservedRows = (if (showStart) 1 else 0) + (if (showEnd) 1 else 0)
                    val titleRows = (totalRows - reservedRows).coerceAtLeast(1)
                    val titleLines = wrap(ev.title, maxChars, titleRows)

                    canvas.drawRoundRect(rect, dp(6f), dp(6f), pEvFill)
                    canvas.drawRoundRect(rect, dp(6f), dp(6f), pEvStroke)

                    // Register hit area ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â store scroll-space rect so touch can compare after adjusting for scroll
                    val rawEv = rawEventsByDay[d]?.firstOrNull { it.title == ev.title && it.startMin == ev.startMin }
                    if (rawEv != null) {
                        eventHitAreas.add(EventHitArea(rawEv, RectF(rect)))
                    }

                    var baseline = innerTop + textBaselineAdjust

                    if (showStart) {
                        drawLineClipped(canvas, formatMinute(ev.startMin), innerLeft, baseline, textWidthPx)
                        baseline += rowStep
                    }

                    for (ln in titleLines) {
                        drawLineClipped(canvas, ln, innerLeft, baseline, textWidthPx)
                        baseline += rowStep
                    }

                    if (showEnd) {
                        val endTxt = formatMinute(ev.endMin)
                        pText.textAlign = Paint.Align.RIGHT
                        canvas.drawText(endTxt, innerRight, innerBottom, pText)
                        pText.textAlign = Paint.Align.LEFT
                    }
                }
            }

            canvas.restore()

            if (showFooter) {
                // Fixed footer/help row
                canvas.drawLine(0f, contentBottom, W, contentBottom, pLine)
                pText.textAlign = Paint.Align.LEFT
                val footerBaseline = H - dp(4f)
                canvas.drawText(context.getString(R.string.label_footer_week), pad, footerBaseline, pText)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true

            val headerH = dp(headerHDp)
            val timeColW = dp(timeColWDp)
            val footerH = if (showFooter) dp(footerHDp) else 0f
            val contentBottom = height.toFloat() - footerH
            val dayColW = (width.toFloat() - timeColW) / 7f

            // Adjust touch Y for scroll offset (hit areas are in scroll-space)
            val touchX = event.x
            val touchY = event.y + scrollPx

            // Only handle touches in the content area
            if (event.y < headerH || event.y > contentBottom) return true

            // Check timed event hit areas first
            val hit = eventHitAreas.firstOrNull { it.rect.contains(touchX, touchY) }
            if (hit != null) {
                onEventClick?.invoke(hit.event)
                return true
            }

            if (event.x >= timeColW) {
                val dayIndex = (((event.x - timeColW) / dayColW).toInt()).coerceIn(0, 6)
                days.getOrNull(dayIndex)?.let { tappedDay ->
                    onDayTap?.invoke((tappedDay.clone() as Calendar))
                }
            }

            return true
        }

        private fun layoutDayEvents(events: List<CalEvent>): List<DayEventLayout> {
            if (events.isEmpty()) return emptyList()

            val sorted = events.sortedWith(compareBy<CalEvent> { it.startMin }.thenBy { it.endMin })
            val laneEnds = mutableListOf<Int>()
            val laneAssignments = mutableListOf<Pair<CalEvent, Int>>()

            for (ev in sorted) {
                var lane = -1
                for (i in laneEnds.indices) {
                    if (ev.startMin >= laneEnds[i]) {
                        lane = i
                        laneEnds[i] = ev.endMin
                        break
                    }
                }
                if (lane == -1) {
                    lane = laneEnds.size
                    laneEnds.add(ev.endMin)
                }
                laneAssignments.add(ev to lane)
            }

            val clusterMax = mutableMapOf<CalEvent, Int>()
            for ((ev, lane) in laneAssignments) {
                val concurrent = sorted.count { other ->
                    other.startMin < ev.endMin && other.endMin > ev.startMin
                }.coerceAtLeast(lane + 1)
                clusterMax[ev] = concurrent
            }

            return laneAssignments.map { (ev, lane) ->
                DayEventLayout(ev, lane, clusterMax[ev] ?: 1)
            }
        }

        private fun yForMinute(totalMin: Int): Float {
            val clamped = totalMin.coerceAtLeast(minHour * 60).coerceAtMost(maxHourExclusive * 60)
            val hour = (clamped / 60).coerceIn(minHour, maxHourExclusive)
            val minutePart = (clamped % 60).coerceIn(0, 59)

            // Resolve exact hour boundaries to the true stored boundary Y.
            if (minutePart == 0) {
                return hourTopMap[hour]
                    ?: hourTopMap[hour - 1]
                    ?: contentStartY
            }

            val topY = hourTopMap[hour]
                ?: hourTopMap[hour - 1]
                ?: contentStartY
            val nextBoundaryY = hourTopMap[hour + 1]
                ?: (topY + (hourHeightMap[hour] ?: dp(hourBlockHDp)))

            return topY + (minutePart / 60f) * (nextBoundaryY - topY)
        }

        private fun buildWeekDays(sel: Calendar, firstDayOfWeek: Int): List<Calendar> {
            val start = sel.clone() as Calendar
            val localeCal = Calendar.getInstance(context.resources.configuration.locales[0] ?: Locale.getDefault())
            start.firstDayOfWeek = firstDayOfWeek
            start.minimalDaysInFirstWeek = localeCal.minimalDaysInFirstWeek
            val diff = (7 + (start.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek)) % 7
            start.add(Calendar.DAY_OF_MONTH, -diff)
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            return (0..6).map { (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, it) } }
        }

        private fun sameDay(a: Calendar, b: Calendar): Boolean =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun formatMinute(totalMin: Int): String {
            val h = totalMin / 60
            val m = totalMin % 60
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }

        private fun wrap(text: String, width: Int, maxLines: Int): List<String> {
            if (width <= 0 || maxLines <= 0) return emptyList()
            val t = text.trim()
            if (t.isEmpty()) return emptyList()

            val words = t.split(Regex("""\s+"""))
            val lines = mutableListOf<String>()
            var cur = ""

            fun flush() {
                if (cur.isNotEmpty()) lines.add(cur)
                cur = ""
            }

            for (w0 in words) {
                var w = w0
                while (w.length > width) {
                    val part = w.take(width)
                    if (cur.isNotEmpty()) flush()
                    lines.add(part)
                    w = w.drop(width)
                    if (lines.size >= maxLines) return lines.take(maxLines)
                }

                val candidate = if (cur.isEmpty()) w else "$cur $w"
                if (candidate.length <= width) {
                    cur = candidate
                } else {
                    flush()
                    cur = w
                    if (lines.size >= maxLines) break
                }
                if (lines.size >= maxLines) break
            }

            flush()
            return lines.take(maxLines)
        }

        private fun estimateHourHeightPx(
            hour: Int,
            timedByDay: List<List<CalEvent>>,
            colWidth: Float,
            dayPad: Float,
            lineH: Float
        ): Float {
            var neededHeight = dp(hourBlockHDp)

            for (d in 0..6) {
                val overlapping = timedByDay[d]
                    .filter { ev -> ev.startMin < (hour + 1) * 60 && ev.endMin > hour * 60 }
                    .sortedWith(compareBy<CalEvent> { it.startMin }.thenBy { it.endMin })

                if (overlapping.isEmpty()) continue

                var dayHeight = 0f
                for (ev in overlapping) {
                    dayHeight += estimateEventPerHourContributionPx(
                        ev = ev,
                        hour = hour,
                        colWidth = colWidth,
                        dayPad = dayPad,
                        lineH = lineH
                    )
                    dayHeight += dp(3f)
                }

                if (dayHeight > neededHeight) neededHeight = dayHeight
            }

            return neededHeight
        }

        private fun estimateEventOuterHeightPx(
            ev: CalEvent,
            colWidth: Float,
            dayPad: Float,
            lineH: Float
        ): Float {
            val innerTextWidthPx = (colWidth - 4 * dayPad).coerceAtLeast(dp(8f))
            val maxChars = (innerTextWidthPx / pText.measureText("M").coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            val showStart = ev.startMin % 60 != 0
            val showEnd = ev.endMin % 60 != 0
            val titleLineCount = wrap(ev.title, maxChars, 999).size.coerceAtLeast(1)
            val rows = titleLineCount + (if (showStart) 1 else 0) + (if (showEnd) 1 else 0)
            val rowStep = lineH + dp(2f)
            val minInnerHeight = rows * rowStep
            val minOuterHeight = minInnerHeight + 2 * dayPad
            return max(dp(hourBlockHDp), minOuterHeight)
        }

        private fun estimateEventPerHourContributionPx(
            ev: CalEvent,
            hour: Int,
            colWidth: Float,
            dayPad: Float,
            lineH: Float
        ): Float {
            val totalOuterHeight = estimateEventOuterHeightPx(
                ev = ev,
                colWidth = colWidth,
                dayPad = dayPad,
                lineH = lineH
            )

            val startHour = ev.startMin / 60
            val endHourExclusive = ((ev.endMin + 59) / 60).coerceAtLeast(startHour + 1)
            val spanHours = (endHourExclusive - startHour).coerceAtLeast(1)

            val distributed = totalOuterHeight / spanHours.toFloat()

            val needsBoundaryRow =
                (hour == startHour && ev.startMin % 60 != 0) ||
                        (hour == endHourExclusive - 1 && ev.endMin % 60 != 0)

            val boundaryMin = if (needsBoundaryRow) (lineH + dp(2f) + 2 * dayPad) else 0f

            return max(distributed, max(dp(16f), boundaryMin))
        }

        private fun computeStackOffsetPx(
            ev: CalEvent,
            dayEvents: List<CalEvent>,
            drawnHeights: Map<CalEvent, Float>,
            colWidth: Float,
            dayPad: Float,
            lineH: Float
        ): Float {
            var offset = 0f
            val evStartHour = ev.startMin / 60
            for (other in dayEvents) {
                if (other == ev) break
                val sameStartHour = (other.startMin / 60) == evStartHour
                if (sameStartHour && other.startMin < ev.endMin && other.endMin > ev.startMin) {
                    offset += drawnHeights[other]
                        ?: estimateEventOuterHeightPx(other, colWidth, dayPad, lineH)
                    offset += dp(3f)
                }
            }
            return offset
        }

        private fun recomputeScroll() {
            val W = width.toFloat()
            val H = height.toFloat()
            if (W <= 0f || H <= 0f) {
                scrollPx = 0f
                maxScrollPx = 0f
                return
            }

            val headerH = dp(headerHDp)
            val gapH = dp(gapHDp)
            val defaultHourBlockH = dp(hourBlockHDp)
            val footerH = if (showFooter) dp(footerHDp) else 0f

            val fm = pText.fontMetrics
            val lineH = (fm.bottom - fm.top)

            var contentH = 0f
            contentH += dp(4f)
            contentH += 3 * (lineH + dp(8f))
            contentH += dp(6f)
            contentH += dp(6f)

            for (slot in slots) {
                when (slot) {
                    is WeekSlot.Gap -> {
                        contentH += gapH
                        contentH += dp(6f)
                    }
                    is WeekSlot.Active -> {
                        contentH += (hourHeightMap[slot.hour] ?: defaultHourBlockH)
                        contentH += dp(6f)
                    }
                }
            }

            val visibleH = (H - headerH - footerH).coerceAtLeast(1f)
            maxScrollPx = (contentH - visibleH).coerceAtLeast(0f)
            val step = (visibleH * 0.90f).coerceAtLeast(dp(24f))
            scrollPx = (pageOffset * step).coerceIn(0f, maxScrollPx)

            invalidate()
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
        private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

        private fun buildSlots(timedByDay: List<List<CalEvent>>, minH: Int, maxH: Int): List<WeekSlot> {
            val slots = mutableListOf<WeekSlot>()
            val hourHasEvents: (Int) -> Boolean = { h ->
                val hStart = h * 60
                val hEnd = hStart + 60
                timedByDay.any { dayEvents ->
                    dayEvents.any { ev -> ev.startMin < hEnd && ev.endMin > hStart }
                }
            }
            var gapStart: Int? = null
            for (h in minH until maxH) {
                if (hourHasEvents(h)) {
                    if (gapStart != null) {
                        slots.add(WeekSlot.Gap(gapStart!!, h - 1))
                        gapStart = null
                    }
                    slots.add(WeekSlot.Active(h))
                } else {
                    if (gapStart == null) gapStart = h
                }
            }
            if (gapStart != null) slots.add(WeekSlot.Gap(gapStart!!, maxH - 1))
            return slots
        }

        private fun computeHourWindow(timedByDay: List<List<CalEvent>>): Pair<Int, Int> {
            val all = timedByDay.flatten()
            if (all.isEmpty()) return 8 to 21
            val minStart = all.minOf { it.startMin }
            val maxEnd = all.maxOf { it.endMin }
            val minHour = (minStart / 60).coerceIn(0, 23)
            val maxHourExclusive = kotlin.math.ceil(maxEnd / 60.0).toInt().coerceIn(1, 24)
            val uiMin = kotlin.math.min(minHour, 8).coerceAtLeast(0)
            val uiMax = kotlin.math.max(maxHourExclusive, 21).coerceAtMost(24)
            return uiMin to uiMax
        }

        private fun drawLineClipped(canvas: Canvas, text: String, x: Float, baselineY: Float, maxWidthPx: Float) {
            canvas.save()
            canvas.clipRect(x, baselineY + pText.fontMetrics.top, x + maxWidthPx, baselineY + pText.fontMetrics.bottom)
            pText.textAlign = Paint.Align.LEFT
            canvas.drawText(text, x, baselineY, pText)
            canvas.restore()
        }
    }



    // DayListCanvasView - simple tappable list for focus view
    class DayListCanvasView(context: android.content.Context) : View(context) {

        var onEventClick: ((CalendarRepository.Event) -> Unit)? = null

        private data class EventHitArea(val event: CalendarRepository.Event, val rect: RectF)
        private data class DayEventCardLayout(
            val event: CalendarRepository.Event,
            val titleLines: List<String>,
            val contentLines: List<String>,
            val cardHeight: Float
        )

        private var selectedDay: Calendar = Calendar.getInstance()
        private var events: List<CalendarRepository.Event> = emptyList()
        private var calendarMap: Map<Long, CalendarRepository.CalInfo> = emptyMap()
        private var showFooter: Boolean = true
        private var pageOffset: Int = 0
        private var darkMode: Boolean = false
        private val hitAreas = mutableListOf<EventHitArea>()

        private val pBg = Paint().apply { color = Color.WHITE }
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = dp(0.6f) }
        private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sp(12f)
            typeface = Typeface.MONOSPACE
        }
        private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sp(12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        private val pCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val pCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(1f) }

        private fun applyPalette() {
            val bg = if (darkMode) Color.BLACK else Color.WHITE
            val fg = if (darkMode) Color.WHITE else Color.BLACK
            val line = if (darkMode) Color.rgb(95, 95, 95) else Color.BLACK
            val card = if (darkMode) Color.rgb(28, 28, 28) else Color.WHITE
            pBg.color = bg
            pLine.color = line
            pText.color = fg
            pTitle.color = fg
            pCardFill.color = card
            pCardStroke.color = fg
        }

        fun update(day: Calendar, events: List<CalendarRepository.Event>, calendarMap: Map<Long, CalendarRepository.CalInfo>, showFooter: Boolean, pageOffset: Int, darkMode: Boolean) {
            selectedDay = day
            this.events = events
            this.calendarMap = calendarMap
            this.showFooter = showFooter
            this.pageOffset = pageOffset
            this.darkMode = darkMode
            applyPalette()
            invalidate()
        }

        private fun buildCardLayouts(
            charsWide: Int,
            pad: Float,
            titleLineH: Float,
            textLineH: Float
        ): List<DayEventCardLayout> {
            return events.map { ev ->
                val timeLabel = if (ev.isAllDay) {
                    context.getString(R.string.label_all_day)
                } else {
                    "${formatMinute(ev.startMin)}-${formatMinute(ev.endMin)}"
                }

                val metaParts = mutableListOf<String>()
                val calName = calendarMap[ev.calendarId]?.name
                if (calName != null) metaParts.add("CAL $calName")
                if (!ev.location.isNullOrBlank()) metaParts.add("LOC ${ev.location}")
                if (!ev.rrule.isNullOrBlank()) metaParts.add("REP ${rruleLabel(ev.rrule)}")
                if (ev.reminderMinutes != null) metaParts.add("REM ${reminderLabel(ev.reminderMinutes, ev.isAllDay)}")

                val titleLines = wrapText(ev.title, charsWide, 3)
                val metaLines = metaParts.flatMap { wrapText(it, charsWide, 2) }
                val descLines = if (ev.displayDescription.isBlank()) emptyList() else wrapText(ev.displayDescription, charsWide, 3)

                val contentLines = mutableListOf<String>()
                contentLines.add(timeLabel)
                contentLines.addAll(metaLines)
                contentLines.addAll(descLines)

                val cardHeight = pad + titleLineH + dp(2f) + contentLines.size * (textLineH + dp(1f)) + pad
                DayEventCardLayout(ev, titleLines, contentLines, cardHeight)
            }
        }

        private fun paginateCards(
            cards: List<DayEventCardLayout>,
            availableHeight: Float,
            rowGap: Float
        ): List<List<DayEventCardLayout>> {
            if (cards.isEmpty()) return listOf(emptyList())

            val pages = mutableListOf<MutableList<DayEventCardLayout>>()
            var currentPage = mutableListOf<DayEventCardLayout>()
            var usedHeight = 0f

            for (card in cards) {
                val extraGap = if (currentPage.isEmpty()) 0f else rowGap
                if (currentPage.isNotEmpty() && usedHeight + extraGap + card.cardHeight > availableHeight) {
                    pages.add(currentPage)
                    currentPage = mutableListOf()
                    usedHeight = 0f
                }
                if (currentPage.isNotEmpty()) usedHeight += rowGap
                currentPage.add(card)
                usedHeight += card.cardHeight
            }

            if (currentPage.isNotEmpty()) pages.add(currentPage)
            return pages
        }

        private fun computeCardPages(): List<List<DayEventCardLayout>> {
            val fallbackMetrics = resources.displayMetrics
            val w = (if (width > 0) width else fallbackMetrics.widthPixels).toFloat()
            val h = (if (height > 0) height else fallbackMetrics.heightPixels).toFloat()

            val pad = dp(6f)
            val rowGap = dp(6f)
            val footerH = if (showFooter) dp(18f) else 0f
            val contentBottom = h - footerH
            val titleFm = pTitle.fontMetrics
            val textFm = pText.fontMetrics
            val titleLineH = titleFm.bottom - titleFm.top
            val textLineH = textFm.bottom - textFm.top
            val innerWidth = (w - 4 * pad).coerceAtLeast(dp(20f))
            val charsWide = (innerWidth / pText.measureText("M").coerceAtLeast(1f)).toInt().coerceAtLeast(6)
            val availableHeight = (contentBottom - pad - dp(4f)).coerceAtLeast(dp(40f))
            return paginateCards(
                buildCardLayouts(charsWide, pad, titleLineH, textLineH),
                availableHeight,
                rowGap
            )
        }

        fun headerPageIndicator(): String? {
            val pages = computeCardPages()
            val totalPages = pages.size.coerceAtLeast(1)
            if (totalPages <= 1) return null
            val currentPage = pageOffset.coerceIn(0, totalPages - 1) + 1
            return "($currentPage/$totalPages)"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            hitAreas.clear()

            val w = width.toFloat()
            val h = height.toFloat()
            val pad = dp(6f)
            val rowGap = dp(6f)
            val cardRadius = dp(6f)
            val footerH = if (showFooter) dp(18f) else 0f
            val contentBottom = h - footerH

            canvas.drawRect(0f, 0f, w, h, pBg)

            val titleFm = pTitle.fontMetrics
            val textFm = pText.fontMetrics
            val titleLineH = titleFm.bottom - titleFm.top
            val textLineH = textFm.bottom - textFm.top
            val titleBaseAdjust = -titleFm.top
            val textBaseAdjust = -textFm.top
            val cardPages = computeCardPages()
            val totalPages = cardPages.size.coerceAtLeast(1)
            val page = pageOffset.coerceIn(0, totalPages - 1)
            var y = pad

            if (events.isEmpty()) {
                canvas.drawText(context.getString(R.string.label_no_events), pad, y + textBaseAdjust, pText)
            } else {
                for (card in cardPages[page]) {
                    val rect = RectF(pad, y, w - pad, y + card.cardHeight)
                    canvas.drawRoundRect(rect, cardRadius, cardRadius, pCardFill)
                    canvas.drawRoundRect(rect, cardRadius, cardRadius, pCardStroke)
                    hitAreas.add(EventHitArea(card.event, RectF(rect)))

                    var baseline = rect.top + pad + titleBaseAdjust
                    drawClippedLine(canvas, card.titleLines.firstOrNull().orEmpty(), rect.left + pad, baseline, rect.width() - 2 * pad, pTitle)
                    baseline += titleLineH + dp(2f)
                    for (line in card.contentLines) {
                        drawClippedLine(canvas, line, rect.left + pad, baseline, rect.width() - 2 * pad, pText)
                        baseline += textLineH + dp(1f)
                    }

                    y += card.cardHeight + rowGap
                }
            }

            if (showFooter) {
                canvas.drawLine(0f, contentBottom, w, contentBottom, pLine)
                val footerText =
                    if (events.isNotEmpty() && totalPages > 1) {
                        context.getString(R.string.label_footer_event_card) +
                            context.getString(R.string.label_footer_page_suffix, page + 1, totalPages)
                    } else {
                        context.getString(R.string.label_footer_event_card)
                    }
                canvas.drawText(footerText, dp(4f), h - dp(4f), pText)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val hit = hitAreas.firstOrNull { it.rect.contains(event.x, event.y) }
            if (hit != null) {
                onEventClick?.invoke(hit.event)
                return true
            }
            return true
        }

        private fun wrapText(text: String, width: Int, maxLines: Int): List<String> {
            if (width <= 0 || maxLines <= 0) return emptyList()
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val words = trimmed.split(Regex("""\s+"""))
            val out = mutableListOf<String>()
            var cur = ""
            fun flush() {
                if (cur.isNotEmpty()) out.add(cur)
                cur = ""
            }
            for (w0 in words) {
                var w = w0
                while (w.length > width) {
                    val part = w.take(width)
                    if (cur.isNotEmpty()) flush()
                    out.add(part)
                    w = w.drop(width)
                    if (out.size >= maxLines) return out.take(maxLines)
                }
                val cand = if (cur.isEmpty()) w else "$cur $w"
                if (cand.length <= width) cur = cand else {
                    flush()
                    cur = w
                    if (out.size >= maxLines) break
                }
            }
            flush()
            return out.take(maxLines)
        }

        private fun drawClippedLine(canvas: Canvas, text: String, x: Float, baselineY: Float, maxWidthPx: Float, paint: Paint) {
            canvas.save()
            canvas.clipRect(x, baselineY + paint.fontMetrics.top, x + maxWidthPx, baselineY + paint.fontMetrics.bottom)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(text, x, baselineY, paint)
            canvas.restore()
        }

        private fun formatMinute(totalMin: Int): String {
            val h = totalMin / 60
            val m = totalMin % 60
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }

        private fun rruleLabel(rrule: String?): String {
            val normalized = rrule?.uppercase(Locale.US).orEmpty()
            return when {
                normalized.contains("FREQ=DAILY") -> context.getString(R.string.label_daily)
                normalized.contains("FREQ=WEEKLY") -> context.getString(R.string.label_weekly)
                normalized.contains("FREQ=MONTHLY") -> context.getString(R.string.label_monthly)
                normalized.contains("FREQ=YEARLY") -> context.getString(R.string.label_yearly)
                else -> context.getString(R.string.label_repeat)
            }
        }

        private fun reminderLabel(minutes: Int?, isAllDay: Boolean): String {
            return when (minutes) {
                null -> context.getString(R.string.label_none)
                0 -> context.getString(R.string.label_at_time_of_event)
                5 -> context.getString(R.string.label_5_min_before)
                10 -> context.getString(R.string.label_10_min_before)
                15 -> context.getString(R.string.label_15_min_before)
                30 -> context.getString(R.string.label_30_min_before)
                60 -> context.getString(R.string.label_1_hour_before)
                1440 -> context.getString(R.string.label_1_day_before)
                2880 -> context.getString(R.string.label_2_days_before)
                10080 -> context.getString(R.string.label_1_week_before)
                else -> if (isAllDay) context.getString(R.string.label_days_before, minutes / 1440) else context.getString(R.string.label_minutes_before, minutes)
            }
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
        private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    }

    // MonthCanvasView ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â graphical month grid
// ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚ÂÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â¢Ãƒâ€šÃ‚Â
    class MonthCanvasView(context: android.content.Context) : View(context) {

        private var selectedDay: Calendar = Calendar.getInstance()
        private var showFooter: Boolean = true
        private var events: List<CalendarRepository.Event> = emptyList()
        private var onDayClick: ((Calendar) -> Unit)? = null
        private var firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek
        private var darkMode: Boolean = false

        data class PlacedBlock(
            val ev: CalendarRepository.Event,
            val weekRow: Int,
            val startCol: Int,
            val endCol: Int,
            val slot: Int,
            val continuesLeft: Boolean,
            val continuesRight: Boolean
        )

        private var weeks: List<List<Calendar>> = emptyList()
        private var placed: List<PlacedBlock> = emptyList()
        private var timedByCell: Map<Pair<Int,Int>, List<CalendarRepository.Event>> = emptyMap()
        private val today: Calendar = Calendar.getInstance()

        fun update(day: Calendar, evs: List<CalendarRepository.Event>, showFooter: Boolean, firstDayOfWeek: Int, darkMode: Boolean) {
            selectedDay = day
            this.showFooter = showFooter
            events = evs
            this.firstDayOfWeek = firstDayOfWeek
            this.darkMode = darkMode
            applyPalette()
            buildLayout()
            invalidate()
        }

        fun setOnDayClick(cb: (Calendar) -> Unit) { onDayClick = cb }

        private fun buildLayout() {
            val year  = selectedDay.get(Calendar.YEAR)
            val month = selectedDay.get(Calendar.MONTH)

            fun startOfVisibleWeek(c: Calendar): Calendar {
                val r = c.clone() as Calendar
                val localeCal = Calendar.getInstance(context.resources.configuration.locales[0] ?: Locale.getDefault())
                r.firstDayOfWeek = firstDayOfWeek
                r.minimalDaysInFirstWeek = localeCal.minimalDaysInFirstWeek
                val diff = (7 + (r.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek)) % 7
                r.add(Calendar.DAY_OF_MONTH, -diff)
                r.set(Calendar.HOUR_OF_DAY, 0); r.set(Calendar.MINUTE, 0)
                r.set(Calendar.SECOND, 0); r.set(Calendar.MILLISECOND, 0)
                return r
            }

            val firstOfMonth = Calendar.getInstance().apply {
                set(year, month, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            val lastOfMonth = Calendar.getInstance().apply {
                set(year, month, firstOfMonth.getActualMaximum(Calendar.DAY_OF_MONTH), 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val wStart = startOfVisibleWeek(firstOfMonth)

            val ws = mutableListOf<List<Calendar>>()
            val cur = wStart.clone() as Calendar
            while (ws.size < 6) {
                val days = (0..6).map { i -> (cur.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) } }
                ws.add(days)
                cur.add(Calendar.WEEK_OF_YEAR, 1)
                // Stop once last day of month is visible (min 4 rows)
                if (ws.size >= 4 && days.last().get(Calendar.MONTH) != month &&
                    days.last().get(Calendar.DAY_OF_MONTH) > 1) break
            }
            weeks = ws

            // Place spanning/all-day events
            val placedList = mutableListOf<PlacedBlock>()
            for ((rowIdx, weekDays) in weeks.withIndex()) {
                val wStartMs = weekDays[0].timeInMillis
                val wEndMs   = weekDays[6].timeInMillis + 86_400_000L

                // For all-day events, compute visible columns using UTC day epochs rather
                // than raw millisecond deltas from local week start. This avoids one-day
                // all-day events appearing as two days around DST boundaries.
                val weekDayEpochs = weekDays.map { day ->
                    val noon = (day.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, 12)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    noon.timeInMillis / 86_400_000L
                }

                // Rad 2097ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œ2100 ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ersÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¤tt spanning-filtret med detta:
                val wStartEpoch = weekDayEpochs.first()
                val wEndEpoch   = weekDayEpochs.last()

                val spanning = events.filter { ev ->
                    if (ev.isAllDay) {
                        val evStartEpoch = ev.startMillis / 86_400_000L
                        val evEndEpoch   = (ev.endMillis - 1) / 86_400_000L
                        evStartEpoch <= wEndEpoch && evEndEpoch >= wStartEpoch
                    } else {
                        ev.endMillis - ev.startMillis >= 86_400_000L &&
                                ev.startMillis < wEndMs && ev.endMillis > wStartMs
                    }
                }.sortedBy { it.startMillis }

                val slots = mutableListOf<Pair<Int,Int>>()
                for (ev in spanning) {
                    val sc: Int
                    val ec: Int
                    val contL: Boolean
                    val contR: Boolean

                    if (ev.isAllDay) {
                        val evStartEpoch = ev.startMillis / 86_400_000L
                        val evEndEpoch   = (ev.endMillis - 1) / 86_400_000L

                        val startIdx = weekDayEpochs.indexOfFirst { it >= evStartEpoch }
                        val endIdx   = weekDayEpochs.indexOfLast { it <= evEndEpoch }

                        sc = (if (startIdx == -1) 7 else startIdx).coerceIn(0, 6)
                        ec = (if (endIdx == -1) -1 else endIdx).coerceIn(0, 6)
                        contL = evStartEpoch < weekDayEpochs.first()
                        contR = evEndEpoch > weekDayEpochs.last()
                    } else {
                        sc = maxOf(0, ((ev.startMillis - wStartMs) / 86_400_000L).toInt())
                        ec = minOf(6, (((ev.endMillis - 1) - wStartMs) / 86_400_000L).toInt())
                        contL = ev.startMillis < wStartMs
                        contR = ev.endMillis > wEndMs
                    }

                    if (ec < sc) continue

                    var slot = 0
                    while (slot < slots.size && slots[slot].first <= ec && slots[slot].second >= sc) slot++
                    while (slots.size <= slot) slots.add(Pair(9,0))
                    slots[slot] = Pair(sc, ec)
                    placedList.add(PlacedBlock(ev, rowIdx, sc, ec, slot, contL, contR))
                }
            }
            placed = placedList

            // Timed events per cell
            val timed = mutableMapOf<Pair<Int,Int>, MutableList<CalendarRepository.Event>>()
            for ((rowIdx, weekDays) in weeks.withIndex()) {
                for ((colIdx, day) in weekDays.withIndex()) {
                    val ds = day.timeInMillis; val de = ds + 86_400_000L
                    events.filter { ev ->
                        !ev.isAllDay && ev.endMillis - ev.startMillis < 86_400_000L &&
                                ev.startMillis >= ds && ev.startMillis < de
                    }.forEach { timed.getOrPut(Pair(rowIdx, colIdx)) { mutableListOf() }.add(it) }
                }
            }
            timedByCell = timed
        }

        private fun weekdayInitials(): List<String> {
            val currentLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            return (0..6).map { offset ->
                val dayOfWeek = ((firstDayOfWeek - 1 + offset) % 7) + 1
                Calendar.getInstance().apply {
                    firstDayOfWeek = this@MonthCanvasView.firstDayOfWeek
                    set(Calendar.DAY_OF_WEEK, dayOfWeek)
                }.let { cal ->
                    SimpleDateFormat("EEE", currentLocale)
                        .format(cal.time)
                        .replace(".", "")
                        .trim()
                        .take(1)
                        .uppercase(currentLocale)
                }
            }
        }

        // Paints ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â simplified month view, same readable font size as week view
        private val FONT = 14f
        private val pBg      = Paint().apply { color = Color.WHITE }
        private val pGrid    = Paint().apply { color = Color.BLACK; strokeWidth = 1f }
        private val pEvFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val pEvStroke= Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f
        }
        private val pText    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.MONOSPACE; textSize = FONT
            isSubpixelText = true
        }
        private val pTextBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = FONT
            isSubpixelText = true
        }
        private val pWeekend = Paint().apply {
            color = Color.rgb(251, 251, 251)
        }

        private fun applyPalette() {
            val bg = if (darkMode) Color.BLACK else Color.WHITE
            val fg = if (darkMode) Color.WHITE else Color.BLACK
            val line = if (darkMode) Color.rgb(95, 95, 95) else Color.BLACK
            val weekend = if (darkMode) Color.rgb(20, 20, 20) else Color.rgb(251, 251, 251)
            val card = if (darkMode) Color.rgb(28, 28, 28) else Color.WHITE
            pBg.color = bg
            pGrid.color = line
            pEvFill.color = card
            pEvStroke.color = fg
            pText.color = fg
            pTextBold.color = fg
            pWeekend.color = weekend
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (weeks.isEmpty()) return

            val W    = width.toFloat()
            val H    = height.toFloat()
            val footerH = if (showFooter) 18f else 0f
            val contentBottom = H - footerH
            val wnW  = 26f
            val colW = (W - wnW) / 7f
            val headerH = 24f
            val rowH    = (contentBottom - headerH) / weeks.size
            val slotH   = 18f
            val dayNumH = 22f
            val curMonth = selectedDay.get(Calendar.MONTH)

            // Day-of-week header
            canvas.drawRect(0f, 0f, W, headerH, pBg)
            weekdayInitials().forEachIndexed { i, d ->
                val cl = wnW + i * colW
                val cr = cl + colW
                canvas.drawRect(cl, 0f, cr, headerH, pBg)
                pTextBold.textAlign = Paint.Align.CENTER
                canvas.drawText(d, (cl + cr) / 2f, headerH - 6f, pTextBold)
            }
            canvas.drawLine(0f, headerH, W, headerH, pGrid)

            for ((rowIdx, weekDays) in weeks.withIndex()) {
                val rowTop    = headerH + rowIdx * rowH
                val rowBottom = rowTop + rowH
                val rowSlots  = placed.filter { it.weekRow == rowIdx }
                val maxSlot   = (rowSlots.maxOfOrNull { it.slot } ?: -1) + 1
                val eventAreaH = maxSlot * slotH + if (maxSlot > 0) 2f else 0f

                // Week number gutter
                pText.textAlign = Paint.Align.CENTER
                val weekNumber = (weekDays[0].clone() as Calendar).apply {
                    firstDayOfWeek = this@MonthCanvasView.firstDayOfWeek
                    minimalDaysInFirstWeek = Calendar.getInstance(context.resources.configuration.locales[0] ?: Locale.getDefault()).minimalDaysInFirstWeek
                }.get(Calendar.WEEK_OF_YEAR)
                canvas.drawText(context.getString(R.string.label_week_number, weekNumber), wnW / 2f, rowTop + 19f, pText)
                canvas.drawLine(wnW, rowTop, wnW, rowBottom, pGrid)

                // Day cells
                for ((colIdx, day) in weekDays.withIndex()) {
                    val cl = wnW + colIdx * colW
                    val cr = cl + colW
                    val isToday = sameDay(day, today)
                    val isWeekend = day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                            day.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

                    canvas.drawRect(cl, rowTop, cr, rowBottom, if (isWeekend) pWeekend else pBg)

                    // Day number ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â centered and bold
                    val dayCenterX = (cl + cr) / 2f
                    val dayTextY = rowTop + 18f
                    pTextBold.textAlign = Paint.Align.CENTER
                    canvas.drawText(day.get(Calendar.DAY_OF_MONTH).toString(), dayCenterX, dayTextY, pTextBold)

                    // Today marker: simple underline under the number
                    if (isToday) {
                        val uy = rowTop + 14f
                        canvas.drawLine(dayCenterX - 10f, uy, dayCenterX + 10f, uy, pGrid)
                    }
                    // Selected day marker: box/rect around the centered day number
                    if (sameDay(day, selectedDay)) {
                        canvas.drawRect(dayCenterX - 14f, rowTop + 2f, dayCenterX + 14f, rowTop + 21f, pEvStroke)
                    }

                    // Timed event titles
                    val timedEvs = timedByCell[Pair(rowIdx, colIdx)] ?: emptyList()
                    val timedTop = rowTop + dayNumH + eventAreaH + 2f
                    var ty = timedTop + 13f
                    val maxShow = maxOf(0, ((rowBottom - 4f - timedTop) / 16f).toInt()).coerceAtMost(3)
                    for (ev in timedEvs.take(maxShow)) {
                        pText.textAlign = Paint.Align.LEFT
                        canvas.save()
                        canvas.clipRect(cl + 4f, ty - 14f, cr - 2f, ty + 3f)
                        canvas.drawText(ev.title, cl + 4f, ty, pText)
                        canvas.restore()
                        ty += 17f
                    }
                    if (timedEvs.size > maxShow) {
                        pText.textAlign = Paint.Align.LEFT
                        canvas.drawText("+${timedEvs.size - maxShow}", cl + 4f, ty, pText)
                    }

                    if (colIdx < 6) canvas.drawLine(cr, rowTop, cr, rowBottom, pGrid)
                }

                // Spanning event blocks
                for (pb in rowSlots) {
                    val bl = wnW + pb.startCol * colW + 2f
                    val br = wnW + (pb.endCol + 1) * colW - 2f
                    val bt = rowTop + dayNumH + pb.slot * slotH + 1f
                    val bb = bt + slotH - 2f
                    val rect = RectF(bl, bt, br, bb)
                    canvas.drawRoundRect(rect, 3f, 3f, pEvFill)
                    canvas.drawRoundRect(rect, 3f, 3f, pEvStroke)
                    val label = pb.ev.title
                    pText.textAlign = Paint.Align.LEFT
                    canvas.save()
                    canvas.clipRect(bl + 3f, bt, br - 3f, bb)
                    canvas.drawText(label, bl + 4f, bb - 3f, pText)
                    canvas.restore()
                }

                canvas.drawLine(0f, rowBottom, W, rowBottom, pGrid)
            }

            if (showFooter) {
                canvas.drawLine(0f, contentBottom, W, contentBottom, pGrid)
                pText.textAlign = Paint.Align.LEFT
                canvas.drawText(context.getString(R.string.label_footer_month), 4f, H - 4f, pText)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP || weeks.isEmpty()) return true
            requestFocus()
            performClick()
            val wnW  = 22f
            val colW = (width - wnW) / 7f
            val headerH = 24f
            val footerH = if (showFooter) 18f else 0f
            val rowH = (height - headerH - footerH) / weeks.size
            if (event.x < wnW || event.y < headerH) return true
            val col = ((event.x - wnW) / colW).toInt().coerceIn(0, 6)
            val row = ((event.y - headerH) / rowH).toInt().coerceIn(0, weeks.size - 1)
            onDayClick?.invoke(weeks[row][col])
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR)  == b.get(Calendar.YEAR) &&
                    a.get(Calendar.MONTH) == b.get(Calendar.MONTH) &&
                    a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH)
    }
}
