package social.tbone.ui.toolbox.calendar

import social.tbone.ui.theme.appBorder
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.calendar.RepeatUnit
import social.tbone.ui.components.LockBadge
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private enum class TimeTarget { START, END }

/**
 * Full-screen calendar event editor. Add events to any minute of any day of
 * any year (1990–2050): the date row opens a smooth month/day picker, the
 * time rows open an analog clock picker. Title + description are encrypted
 * (AES-256-GCM, Android Keystore) before being stored. Saves on the save
 * button and again when leaving with content, so nothing is lost.
 */
private data class CalendarEditorForm(
    val title: String = "",
    val description: String = "",
    val eventDate: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    val startTime: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val endTime: LocalTime = LocalTime.now().plusHours(1).withSecond(0).withNano(0),
    val allDay: Boolean = false,
    val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    val repeatInterval: Int = 1,
    val repeatEndDate: LocalDate? = null,
)

private fun eventStartMillis(form: CalendarEditorForm): Long =
    if (form.allDay) form.eventDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    else form.eventDate.atTime(form.startTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun eventEndMillis(form: CalendarEditorForm): Long {
    if (form.allDay) {
        return form.eventDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val zone = ZoneId.systemDefault()
    val end = if (form.endTime > form.startTime) form.eventDate.atTime(form.endTime)
        else form.eventDate.plusDays(1).atTime(form.endTime)
    return end.atZone(zone).toInstant().toEpochMilli()
}

/**
 * Full-screen calendar event editor. Add events to any minute of any day of
 * any year (1990–2050): the date row opens a smooth month/day picker, the
 * time rows open an analog clock picker. Title + description are encrypted
 * (AES-256-GCM, Android Keystore) before being stored. Saves on the save
 * button and again when leaving with content, so nothing is lost.
 */
@Composable
fun CalendarEventEditorScreen(
    id: String,
    dateMillis: Long,
    onBack: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val encryptionVerified by viewModel.encryptionVerified.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var form by remember { mutableStateOf(CalendarEditorForm()) }
    var savedId by remember { mutableStateOf<String?>(if (id.isBlank()) null else id) }
    var lastSavedForm by remember { mutableStateOf<CalendarEditorForm?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var skipDisposeSave by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    var showRepeatPicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var clockTarget by remember { mutableStateOf<TimeTarget?>(null) }

    val context = LocalContext.current
    val permPromptDone = remember {
        context.getSharedPreferences("tbone_calendar", Context.MODE_PRIVATE)
            .getBoolean("perm_prompt_done", false)
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(id, dateMillis) {
        try {
        if (id.isNotBlank()) {
            (viewModel.getEvent(id) ?: throw IllegalStateException("Event not found")).let { event ->
                val zone = ZoneId.systemDefault()
                val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
                form = form.copy(
                    title = event.title,
                    description = event.description,
                    eventDate = start.toLocalDate(),
                    startTime = start.toLocalTime(),
                    endTime = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalTime(),
                    allDay = event.allDay,
                    repeatUnit = event.repeatUnit,
                    repeatInterval = event.repeatInterval,
                    repeatEndDate = event.repeatEndMillis?.let {
                        Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                    },
                )
                savedId = event.id
                lastSavedForm = form
            }
        } else if (dateMillis > 0) {
            form = form.copy(
                eventDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
            )
        }
            loaded = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadFailed = true
        }
    }

    fun performSave(onError: (Exception) -> Unit = {}, onDone: () -> Unit = {}) {
        if (deleted || !loaded || form.title.isBlank()) return
        val submitted = form
        val isNew = savedId.isNullOrBlank()
        if (isNew) savedId = java.util.UUID.randomUUID().toString()
        val targetId = savedId!!
        viewModel.saveEvent(
            id = targetId,
            title = submitted.title,
            description = submitted.description,
            startMillis = eventStartMillis(submitted),
            endMillis = eventEndMillis(submitted),
            allDay = submitted.allDay,
            repeatUnit = submitted.repeatUnit,
            repeatInterval = submitted.repeatInterval,
            repeatEndMillis = submitted.repeatEndDate
                ?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli(),
            onError = onError,
        ) { newId ->
            savedId = newId
            lastSavedForm = submitted
            if (isNew && !permPromptDone) {
                context.getSharedPreferences("tbone_calendar", Context.MODE_PRIVATE)
                    .edit().putBoolean("perm_prompt_done", true).apply()
                showPermDialog = true
            }
            onDone()
        }
    }

    val saveOnExit by rememberUpdatedState({ if (!skipDisposeSave && !deleted && form != lastSavedForm) performSave() })
    DisposableEffect(Unit) { onDispose { saveOnExit() } }

    fun back() {
        if (exiting || deleted) return
        if (!loaded || (savedId == null && form.title.isBlank())) { skipDisposeSave = true; onBack(); return }
        if (form.title.isBlank()) {
            android.widget.Toast.makeText(context, "An event needs a title before it can be saved.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        exiting = true
        fun saveBeforeLeaving() {
            if (form.title.isBlank()) {
                exiting = false
                android.widget.Toast.makeText(context, "An event needs a title before it can be saved.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val submitted = form
            performSave(onError = { exiting = false }) {
                if (form != submitted) saveBeforeLeaving()
                else { skipDisposeSave = true; onBack() }
            }
        }
        saveBeforeLeaving()
    }
    BackHandler { back() }

    if (!loaded) {
        Box(Modifier.fillMaxSize().background(BonyColors.Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (loadFailed) "event unavailable" else "loading event…", color = BonyColors.TextMute)
                Text("← back", Modifier.clickable { back() }.padding(16.dp), color = BonyColors.Accent)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        CalendarEditorTopBar(
            savedId = savedId,
            encryptionVerified = encryptionVerified,
            onBack = { back() },
            onDelete = {
                if (!deleted && !exiting) {
                    deleted = true
                    val target = savedId
                    if (target == null) { skipDisposeSave = true; onBack() }
                    else viewModel.deleteEvent(target, onError = { deleted = false }) {
                        skipDisposeSave = true
                        onBack()
                    }
                }
            },
            onSave = { back() },
        )
        error?.let { CalendarEditorErrorBanner(it) }
        CalendarEditorFields(
            form = form,
            onFormChange = { form = it },
            onDateClick = { showDatePicker = true },
            onRepeatClick = { showRepeatPicker = true },
            onEndDateClick = { showEndDatePicker = true },
            onStartTimeClick = { clockTarget = TimeTarget.START },
            onEndTimeClick = { clockTarget = TimeTarget.END },
        )
    }

    CalendarEditorOverlays(
        form = form,
        onFormChange = { form = it },
        showDatePicker = showDatePicker,
        onShowDatePickerChange = { showDatePicker = it },
        clockTarget = clockTarget,
        onClockTargetChange = { clockTarget = it },
        showEndDatePicker = showEndDatePicker,
        onShowEndDatePickerChange = { showEndDatePicker = it },
        showRepeatPicker = showRepeatPicker,
        onShowRepeatPickerChange = { showRepeatPicker = it },
    )

    // Reminder-permission popup (once, after the first saved event).
    if (showPermDialog) {
        ReminderPermDialog(
            onEnable = {
                showPermDialog = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + context.packageName),
                            ),
                        )
                    }
                }
            },
            onLater = { showPermDialog = false },
        )
    }
}

@Composable
private fun CalendarEditorTopBar(
    savedId: String?,
    encryptionVerified: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            style = BonyType.body.copy(color = BonyColors.TextMute),
            modifier = Modifier.clickable(onClick = onBack),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (savedId == null) "new event" else "event",
            style = BonyType.body.copy(color = BonyColors.Text),
            modifier = Modifier.weight(1f),
        )
        if (savedId != null) {
            Text(
                text = "delete",
                style = BonyType.tag.copy(color = BonyColors.Danger),
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.Danger)
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "save",
            style = BonyType.tag.copy(
                color = if (encryptionVerified) BonyColors.Accent else BonyColors.TextMute,
            ),
            modifier = Modifier
                .appBorder(1.dp, if (encryptionVerified) BonyColors.AccentDim else BonyColors.Rule)
                .clickable(enabled = encryptionVerified, onClick = onSave)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun CalendarEditorErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Danger.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = message, style = BonyType.meta.copy(color = BonyColors.Danger))
    }
}

@Composable
private fun CalendarEditorFields(
    form: CalendarEditorForm,
    onFormChange: (CalendarEditorForm) -> Unit,
    onDateClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onEndDateClick: () -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        OutlinedTextField(
            value = form.title,
            onValueChange = { onFormChange(form.copy(title = it)) },
            placeholder = { Text("event title", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            singleLine = true,
            textStyle = BonyType.body.copy(color = BonyColors.Text),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            placeholder = { Text("notes…", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            minLines = 3,
            textStyle = BonyType.body.copy(color = BonyColors.Text),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        FieldRow("date", formatDateLong(form.eventDate), onDateClick)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "all day",
                style = BonyType.meta.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(36.dp, 22.dp)
                    .background(if (form.allDay) BonyColors.AccentBg else BonyColors.Bg)
                    .appBorder(1.dp, if (form.allDay) BonyColors.Accent else BonyColors.RuleStrong)
                    .clickable { onFormChange(form.copy(allDay = !form.allDay)) }
                    .padding(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(if (form.allDay) Alignment.CenterEnd else Alignment.CenterStart)
                        .background(if (form.allDay) BonyColors.Accent else BonyColors.TextMute),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .appBorder(1.dp, BonyColors.Rule)
                .clickable(onClick = onRepeatClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "repeat",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(0.35f),
            )
            Text(
                text = if (form.repeatUnit == RepeatUnit.NONE) "never" else
                    (if (form.repeatInterval == 1) "every ${form.repeatUnit.label.dropLast(1)}"
                    else "every ${form.repeatInterval} ${form.repeatUnit.label}"),
                style = BonyType.body.copy(color = BonyColors.Accent),
                modifier = Modifier.weight(1f),
            )
            Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
        }

        if (form.repeatUnit != RepeatUnit.NONE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .appBorder(1.dp, BonyColors.Rule)
                    .clickable(onClick = onEndDateClick)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ends",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.weight(0.35f),
                )
                Text(
                    text = form.repeatEndDate?.let { formatDateLong(it) } ?: "never",
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!form.allDay) {
            FieldRow("start", formatTime(eventStartMillis(form)), onStartTimeClick)
            FieldRow("end", formatTime(eventEndMillis(form)), onEndTimeClick)
        }
        Spacer(Modifier.height(8.dp))
        LockBadge(label = "encrypted on device · AES-256-GCM")
    }
}

@Composable
private fun CalendarEditorOverlays(
    form: CalendarEditorForm,
    onFormChange: (CalendarEditorForm) -> Unit,
    showDatePicker: Boolean,
    onShowDatePickerChange: (Boolean) -> Unit,
    clockTarget: TimeTarget?,
    onClockTargetChange: (TimeTarget?) -> Unit,
    showEndDatePicker: Boolean,
    onShowEndDatePickerChange: (Boolean) -> Unit,
    showRepeatPicker: Boolean,
    onShowRepeatPickerChange: (Boolean) -> Unit,
) {
    if (showDatePicker) {
        MonthDayPicker(
            initial = form.eventDate,
            onSelect = { onFormChange(form.copy(eventDate = it)); onShowDatePickerChange(false) },
            onDismiss = { onShowDatePickerChange(false) },
        )
    }
    clockTarget?.let { target ->
        ClockPicker(
            initial = if (target == TimeTarget.START) form.startTime else form.endTime,
            onConfirm = { time ->
                onFormChange(
                    if (target == TimeTarget.START) form.copy(startTime = time)
                    else form.copy(endTime = time),
                )
                onClockTargetChange(null)
            },
            onDismiss = { onClockTargetChange(null) },
        )
    }

    if (showEndDatePicker) {
        Box(modifier = Modifier.fillMaxSize()) {
            MonthDayPicker(
                initial = form.repeatEndDate ?: form.eventDate,
                onSelect = {
                    onFormChange(form.copy(repeatEndDate = it))
                    onShowEndDatePickerChange(false)
                },
                onDismiss = { onShowEndDatePickerChange(false) },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp)
                    .appBorder(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .clickable {
                        onFormChange(form.copy(repeatEndDate = null))
                        onShowEndDatePickerChange(false)
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "no end date", style = BonyType.tag.copy(color = BonyColors.Accent))
            }
        }
    }

    if (showRepeatPicker) {
        RepeatPickerOverlay(
            unit = form.repeatUnit,
            interval = form.repeatInterval,
            onDismiss = { onShowRepeatPickerChange(false) },
            onApply = { unit, interval ->
                onFormChange(form.copy(repeatUnit = unit, repeatInterval = interval))
                onShowRepeatPickerChange(false)
            },
        )
    }
}


/** Asks for event-reminder permissions, styled like the rest of the app. */
@Composable
private fun ReminderPermDialog(
    onEnable: () -> Unit,
    onLater: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.85f))
            .clickable(onClick = onLater),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text(
                text = "enable event reminders?",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Calendar can remind you 10 minutes before each event, " +
                    "fully offline. This needs notification permission and " +
                    "exact-alarm access.",
                style = BonyType.meta.copy(color = BonyColors.TextDim),
                modifier = Modifier.padding(bottom = 14.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "later",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable(onClick = onLater)
                        .padding(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "enable",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .appBorder(1.dp, BonyColors.AccentDim)
                        .clickable(onClick = onEnable)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appBorder(1.dp, BonyColors.Rule)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = BonyType.body.copy(color = BonyColors.Accent),
            modifier = Modifier.weight(1f),
        )
        Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Spacer(Modifier.height(8.dp))
}

/** Recurrence selector — pick a unit and any interval, styled like the app. */
@Composable
private fun RepeatPickerOverlay(
    unit: RepeatUnit,
    interval: Int,
    onDismiss: () -> Unit,
    onApply: (RepeatUnit, Int) -> Unit,
) {
    var u by remember { mutableStateOf(unit) }
    var n by remember { mutableStateOf(interval.coerceAtLeast(1)) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text(
                text = "repeat",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            // Unit chips.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                RepeatUnit.entries.forEach { candidate ->
                    Text(
                        text = candidate.label,
                        style = BonyType.tag.copy(
                            color = if (u == candidate) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .appBorder(
                                1.dp,
                                if (u == candidate) BonyColors.Accent else BonyColors.Rule,
                            )
                            .clickable {
                                u = candidate
                                if (candidate == RepeatUnit.NONE) n = 1
                            }
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // Interval stepper (hidden when never).
            if (u != RepeatUnit.NONE) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "every",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "−",
                        style = BonyType.body.copy(color = BonyColors.TextMute),
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.Rule)
                            .clickable { if (n > 1) n-- }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "$n",
                        style = BonyType.body.copy(color = BonyColors.Accent),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "+",
                        style = BonyType.body.copy(color = BonyColors.TextMute),
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.Rule)
                            .clickable { if (n < 365) n++ }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = u.label,
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "cancel",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "ok",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .appBorder(1.dp, BonyColors.AccentDim)
                        .clickable { onApply(u, if (u == RepeatUnit.NONE) 0 else n) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
