package social.tbone.ui.toolbox.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The calendar covers every month from 1990 through 2050 (inclusive). */
const val CALENDAR_START_YEAR = 1990
const val CALENDAR_END_YEAR = 2050

/** Number of month items in the scrollable range. */
val TOTAL_MONTHS: Int = (CALENDAR_END_YEAR - CALENDAR_START_YEAR + 1) * 12

/** Month at a scroll index (0 = January 1990). */
fun monthAt(index: Int): YearMonth =
    YearMonth.of(CALENDAR_START_YEAR, 1).plusMonths(index.toLong())

/** Scroll index for a date's month. */
fun monthIndexOf(date: LocalDate): Int =
    (date.year - CALENDAR_START_YEAR) * 12 + (date.monthValue - 1)

/** Today in the system zone. */
fun todayLocal(): LocalDate = LocalDate.now(ZoneId.systemDefault())

private val WEEKDAY_HEADER = listOf("S", "M", "T", "W", "T", "F", "S")

private val MONTH_NAME = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DATE_LONG = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val TIME_SHORT = DateTimeFormatter.ofPattern("h:mm a")

fun formatMonth(month: YearMonth): String = MONTH_NAME.format(month)
fun formatDateLong(date: LocalDate): String = DATE_LONG.format(date)
fun formatTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_SHORT)

/**
 * A complete month grid — a proper grid of squares: vertical + horizontal
 * thin lines (plus a top line under the weekday header) so every day reads as
 * a square. The day number sits at the very top of the cell, leaving the rest
 * for event titles. Tapping a square draws a thin line around the ENTIRE
 * square (not just the number); today's number is bold in the cube font.
 */
@Composable
fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    eventDates: Set<LocalDate>,
    onDayClick: (LocalDate) -> Unit,
    eventsByDay: Map<LocalDate, List<String>> = emptyMap(),
    monthHeader: (@Composable () -> Unit)? = null,
    /** Fixed row height for compact contexts (the date picker); null = fill the available height (calendar). */
    cellHeight: Dp? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        monthHeader?.invoke()
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            WEEKDAY_HEADER.forEach { d ->
                Text(
                    text = d,
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Top line of the grid.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        val first = month.atDay(1)
        val offset = first.dayOfWeek.value % 7 // Sunday-first: Sun=0 … Sat=6
        val days = month.lengthOfMonth()
        val cells = (0 until 42).map { idx ->
            val dayNum = idx - offset + 1
            if (dayNum in 1..days) month.atDay(dayNum) else null
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (cellHeight == null) Modifier.weight(1f) else Modifier),
        ) {
            cells.chunked(7).forEachIndexed { weekIndex, week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (cellHeight != null) Modifier.height(cellHeight)
                            else Modifier.weight(1f)
                        ),
                ) {
                    week.forEachIndexed { colIndex, date ->
                        DayCell(
                            date = date,
                            today = today,
                            selected = selected,
                            titles = if (date != null) eventsByDay[date].orEmpty() else emptyList(),
                            onClick = { date?.let(onDayClick) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                        // Vertical divider between columns.
                        if (colIndex < 6) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(BonyColors.Rule),
                            )
                        }
                    }
                }
                // Horizontal line between weeks.
                if (weekIndex < 5) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
            }
        }
    }
}

/** One grid square: number at the top, event titles below, selection ring. */
@Composable
private fun DayCell(
    date: LocalDate?,
    today: LocalDate,
    selected: LocalDate?,
    titles: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday = date == today
    val isSelected = date != null && date == selected
    Box(
        modifier = modifier
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Selection = thin line around the ENTIRE square.
            .then(if (isSelected) Modifier.border(1.dp, BonyColors.Accent) else Modifier),
    ) {
        if (date != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Day number all the way at the top.
                Text(
                    text = "${date.dayOfMonth}",
                    style = BonyType.meta.copy(
                        color = if (isToday || isSelected) BonyColors.Accent else BonyColors.Text,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    ),
                    maxLines = 1,
                )
                // Event titles below — a good bit of text fits in the square.
                titles.take(2).forEach { t ->
                    Text(
                        text = t,
                        style = BonyType.caption.copy(color = BonyColors.Accent),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (titles.size > 2) {
                    Text(
                        text = "+${titles.size - 2}",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
