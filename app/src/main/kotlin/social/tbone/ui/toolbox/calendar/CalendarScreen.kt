package social.tbone.ui.toolbox.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.calendar.CalendarEventUi
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NOW_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")

/**
 * The Calendar tool — one month fills the screen and you SWIPE left/right to
 * move between months (1990–2050), no month buttons. A square surround marks
 * today and moves to any day you tap; days with events show their titles in
 * the accent color. Events are AES-256-GCM encrypted on-device; the header
 * shows a live clock so the current time is always exactly right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onNewEvent: (Long) -> Unit,
    onEditEvent: (String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {

    val events by viewModel.events.collectAsStateWithLifecycle()
    val encryptionVerified by viewModel.encryptionVerified.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val today = remember { todayLocal() }

    // Occurrences for a given month — computed on demand per page with a
    // range-bounded query, so repeating events render on every occurrence
    // while old long-running repeats stay cheap (the pager only composes the
    // visible pages).
    fun monthEventDates(month: YearMonth): Set<LocalDate> {
        val z = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(z).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
        return events.flatMap { ev -> ev.occurrenceStartsInRange(from, to) }
            .map { Instant.ofEpochMilli(it).atZone(z).toLocalDate() }
            .toSet()
    }

    fun monthEventsByDay(month: YearMonth): Map<LocalDate, List<String>> {
        val z = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(z).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(z).toInstant().toEpochMilli() - 1
        return events.flatMap { ev ->
            ev.occurrenceStartsInRange(from, to)
                .map { Instant.ofEpochMilli(it).atZone(z).toLocalDate() to ev.title }
        }.groupBy({ it.first }, { it.second })
    }

    // Live clock — ticks every second so the header time is exactly right.
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val nowText = remember(nowMillis) {
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).format(NOW_FORMAT)
    }

    // One month per page; horizontal snap paging (swipe left/right).
    val pagerState = rememberPagerState(
        initialPage = monthIndexOf(today).coerceIn(0, TOTAL_MONTHS - 1),
        pageCount = { TOTAL_MONTHS },
    )
    val visibleMonth by remember {
        derivedStateOf {
            monthAt(pagerState.currentPage.coerceIn(0, TOTAL_MONTHS - 1))
        }
    }

    var jumpPicker by remember { mutableStateOf(false) }
    var daySheet by remember { mutableStateOf<java.time.LocalDate?>(null) }
    // The square selection ring moves to whichever day you tap.
    var selectedDay by remember { mutableStateOf(today) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg)
                .statusBarsPadding(),
        ) {
            // ── Top bar: back · title · live clock ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "calendar",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = nowText,
                    style = BonyType.metaDim.copy(
                        color = if (encryptionVerified) BonyColors.Accent else BonyColors.Danger,
                    ),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            // ── Month row: swipe to change months; tap the name to jump ───────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMonth(visibleMonth),
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { jumpPicker = true }
                        .padding(vertical = 8.dp),
                )
                Text(
                    text = "today",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .border(1.dp, BonyColors.Rule)
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(monthIndexOf(today)) }
                            selectedDay = today
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

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

            // ── One month fills the screen; swipe left/right to change ────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                // A small gap between months that only shows while scrolling —
                // at rest the current month's lines run edge to edge.
                pageSpacing = 10.dp,
                contentPadding = PaddingValues(bottom = 64.dp),
            ) { page ->
                val month = monthAt(page)
                MonthGrid(
                    month = month,
                    today = today,
                    selected = selectedDay,
                    eventDates = monthEventDates(month),
                    eventsByDay = monthEventsByDay(month),
                    onDayClick = { day ->
                        selectedDay = day
                        daySheet = day
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ── Floating add button (unchanged position) ───────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 12.dp)
                .border(1.dp, BonyColors.AccentDim)
                .background(BonyColors.Surface)
                .clickable { onNewEvent(todayStartMillis(today)) }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(text = "+ EVENT", style = BonyType.button.copy(color = BonyColors.Accent))
        }
    }

    // Jump-to-month picker.
    if (jumpPicker) {
        MonthDayPicker(
            initial = visibleMonth.atDay(1).coerceIn(
                java.time.LocalDate.of(CALENDAR_START_YEAR, 1, 1),
                java.time.LocalDate.of(CALENDAR_END_YEAR, 12, 31),
            ),
            onSelect = { date ->
                jumpPicker = false
                selectedDay = date
                scope.launch { pagerState.animateScrollToPage(monthIndexOf(date)) }
            },
            onDismiss = { jumpPicker = false },
        )
    }

    // Day sheet: events for the tapped day + add.
    daySheet?.let { day ->
        ModalBottomSheet(
            onDismissRequest = { daySheet = null },
            containerColor = BonyColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    text = formatDateLong(day),
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                val dayEvents = events.filter { it.hasOccurrenceOn(day) }
                if (dayEvents.isEmpty()) {
                    Text(
                        text = "no events on this day",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                dayEvents.forEach { event ->
                    val starts = event.occurrenceStartsOn(day)
                    DayEventRow(event, starts) { onEditEvent(event.id) }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+ add event",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clickable {
                            daySheet = null
                            onNewEvent(todayStartMillis(day))
                        },
                )
            }
        }
    }
}

@Composable
private fun DayEventRow(
    event: CalendarEventUi,
    occurrenceStarts: List<Long>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = event.title,
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (event.isRepeating) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "↻",
                    style = BonyType.metaDim.copy(color = BonyColors.Accent),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val times = if (event.allDay) "all day"
            else occurrenceStarts.joinToString(", ") { formatTime(it) }
        Text(
            text = times,
            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event.isRepeating) {
            Text(
                text = event.repeatLabel(),
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                maxLines = 1,
            )
        }
    }
}

private fun todayStartMillis(date: java.time.LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
