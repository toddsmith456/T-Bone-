package social.tbone.ui.toolbox.calendar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
@Composable
fun CalendarEventEditorScreen(
    id: String,
    dateMillis: Long,
    onBack: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {

    val encryptionVerified by viewModel.encryptionVerified.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var eventDate by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    var startTime by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var endTime by remember { mutableStateOf(LocalTime.now().plusHours(1).withSecond(0).withNano(0)) }
    var allDay by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf<String?>(if (id.isBlank()) null else id) }
    var loaded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    // Recurrence.
    var repeatUnit by remember { mutableStateOf(RepeatUnit.NONE) }
    var repeatInterval by remember { mutableStateOf(1) }
    var repeatEndDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var showRepeatPicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    // Guard against double-save (see performSave).
    var saveInFlight by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permPromptDone = remember {
        context.getSharedPreferences("tbone_calendar", Context.MODE_PRIVATE)
            .getBoolean("perm_prompt_done", false)
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var showDatePicker by remember { mutableStateOf(false) }
    var clockTarget by remember { mutableStateOf<TimeTarget?>(null) }

    // Load the existing event (if editing).
    LaunchedEffect(id, dateMillis) {
        if (id.isNotBlank()) {
            viewModel.getEvent(id)?.let { event ->
                title = event.title
                description = event.description
                val z = ZoneId.systemDefault()
                val start = Instant.ofEpochMilli(event.startMillis).atZone(z)
                eventDate = start.toLocalDate()
                startTime = start.toLocalTime()
                endTime = Instant.ofEpochMilli(event.endMillis).atZone(z).toLocalTime()
                allDay = event.allDay
                repeatUnit = event.repeatUnit
                repeatInterval = event.repeatInterval
                repeatEndDate = event.repeatEndMillis?.let {
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                }
                savedId = event.id
            }
        } else if (dateMillis > 0) {
            eventDate = Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        loaded = true
    }

    fun startMillis(): Long =
        if (allDay) eventDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        else eventDate.atTime(startTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endMillis(): Long {
        if (allDay) {
            return eventDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val z = ZoneId.systemDefault()
        val end = if (endTime > startTime) eventDate.atTime(endTime) else eventDate.plusDays(1).atTime(endTime)
        return end.atZone(z).toInstant().toEpochMilli()
    }

    fun performSave() {
        if (title.isBlank()) return
        // Re-entrancy guard: save can fire from the button AND from leaving
        // the screen. Reserve the row id synchronously so a second call
        // upserts the SAME row instead of creating a duplicate event.
        if (saveInFlight) return
        saveInFlight = true
        val isNew = savedId.isNullOrBlank()
        if (isNew) savedId = java.util.UUID.randomUUID().toString()
        val targetId = savedId!!
        viewModel.saveEvent(
            id = targetId,
            title = title,
            description = description,
            startMillis = startMillis(),
            endMillis = endMillis(),
            allDay = allDay,
            repeatUnit = repeatUnit,
            repeatInterval = repeatInterval,
            repeatEndMillis = repeatEndDate
                ?.atStartOfDay(java.time.ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli(),
        ) { newId ->
            savedId = newId
            saved = true
            saveInFlight = false
            // First event saved since the update -> offer the reminder
            // permissions (notifications + exact alarms) in a popup.
            if (isNew && !permPromptDone) {
                context.getSharedPreferences("tbone_calendar", Context.MODE_PRIVATE)
                    .edit().putBoolean("perm_prompt_done", true).apply()
                showPermDialog = true
            }
        }
    }

    // Save on leave too (back button / system back) — never lose an event.
    val saveOnExit by rememberUpdatedState({ if (!saved) performSave() })
    DisposableEffect(Unit) {
        onDispose { saveOnExit() }
    }

    fun back() {
        saveOnExit()
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // ── Top bar: back · title · save (and delete when editing) ────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { back() },
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
                        .border(1.dp, BonyColors.Danger)
                        .clickable {
                            savedId?.let { viewModel.deleteEvent(it) }
                            onBack()
                        }
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
                    .border(1.dp, if (encryptionVerified) BonyColors.AccentDim else BonyColors.Rule)
                    // Save always closes the editor — even when there's nothing
                    // new to save (performSave no-ops on blank/unchanged).
                    .clickable(enabled = encryptionVerified) {
                        performSave()
                        back()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Danger.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(text = error.orEmpty(), style = BonyType.meta.copy(color = BonyColors.Danger))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("event title", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text("notes…", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                minLines = 3,
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // ── Date row → date picker ─────────────────────────────────────────
            FieldRow("date", formatDateLong(eventDate)) { showDatePicker = true }

            // ── All-day toggle ─────────────────────────────────────────────────
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
                        .background(if (allDay) BonyColors.AccentBg else BonyColors.Bg)
                        .border(1.dp, if (allDay) BonyColors.Accent else BonyColors.RuleStrong)
                        .clickable { allDay = !allDay }
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(if (allDay) Alignment.CenterEnd else Alignment.CenterStart)
                            .background(if (allDay) BonyColors.Accent else BonyColors.TextMute),
                    )
                }
            }

            // ── Repeat row → repeat picker ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BonyColors.Rule)
                    .clickable { showRepeatPicker = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "repeat",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.weight(0.35f),
                )
                Text(
                    text = if (repeatUnit == RepeatUnit.NONE) "never" else
                        (if (repeatInterval == 1) "every ${repeatUnit.label.dropLast(1)}"
                        else "every $repeatInterval ${repeatUnit.label}"),
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    modifier = Modifier.weight(1f),
                )
                Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
            }
            // ── Ends row (only when repeating) ─────────────────────────────────
            if (repeatUnit != RepeatUnit.NONE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BonyColors.Rule)
                        .clickable { showEndDatePicker = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ends",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                        modifier = Modifier.weight(0.35f),
                    )
                    Text(
                        text = repeatEndDate?.let { formatDateLong(it) } ?: "never",
                        style = BonyType.body.copy(color = BonyColors.Accent),
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Time rows → clock picker (hidden for all-day) ──────────────────
            if (!allDay) {
                FieldRow("start", formatTime(startMillis())) { clockTarget = TimeTarget.START }
                FieldRow("end", formatTime(endMillis())) { clockTarget = TimeTarget.END }
            }

            Spacer(Modifier.height(8.dp))
            LockBadge(label = "encrypted on device · AES-256-GCM")
        }
    }

    // ── Overlays ──────────────────────────────────────────────────────────────
    if (showDatePicker) {
        MonthDayPicker(
            initial = eventDate,
            onSelect = { eventDate = it; showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    clockTarget?.let { target ->
        ClockPicker(
            initial = if (target == TimeTarget.START) startTime else endTime,
            onConfirm = { time ->
                if (target == TimeTarget.START) startTime = time else endTime = time
                clockTarget = null
            },
            onDismiss = { clockTarget = null },
        )
    }

    // End-date picker overlay (uses the same date picker as the event date),
    // with a "no end date" button pinned under it.
    if (showEndDatePicker) {
        Box(modifier = Modifier.fillMaxSize()) {
            MonthDayPicker(
                initial = repeatEndDate ?: eventDate,
                onSelect = { repeatEndDate = it; showEndDatePicker = false },
                onDismiss = { showEndDatePicker = false },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp)
                    .border(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .clickable { repeatEndDate = null; showEndDatePicker = false }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "no end date", style = BonyType.tag.copy(color = BonyColors.Accent))
            }
        }
    }

    // Repeat picker overlay.
    if (showRepeatPicker) {
        RepeatPickerOverlay(
            unit = repeatUnit,
            interval = repeatInterval,
            onDismiss = { showRepeatPicker = false },
            onApply = { unit, interval ->
                repeatUnit = unit
                repeatInterval = interval
                showRepeatPicker = false
            },
        )
    }

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
                .border(1.dp, BonyColors.RuleStrong)
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
                        .border(1.dp, BonyColors.AccentDim)
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
            .border(1.dp, BonyColors.Rule)
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
                .border(1.dp, BonyColors.RuleStrong)
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
                            .border(
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
                            .border(1.dp, BonyColors.Rule)
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
                            .border(1.dp, BonyColors.Rule)
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
                        .border(1.dp, BonyColors.AccentDim)
                        .clickable { onApply(u, if (u == RepeatUnit.NONE) 0 else n) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
