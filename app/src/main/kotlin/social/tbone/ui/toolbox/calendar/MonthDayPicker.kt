package social.tbone.ui.toolbox.calendar

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.LocalDate
import java.time.YearMonth

/**
 * A smooth date picker in the app's style: ◀ ▶ paging through months with a
 * tap-to-open year list covering 1990–2050, and a day grid to pick the day.
 */
@Composable
fun MonthDayPicker(
    initial: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var selected by remember { mutableStateOf(initial) }
    var showYearList by remember { mutableStateOf(false) }

    // System back closes the month view.
    BackHandler { onDismiss() }

    fun clampMonth(y: Int, m: Int): YearMonth =
        YearMonth.of(y.coerceIn(CALENDAR_START_YEAR, CALENDAR_END_YEAR), m.coerceIn(1, 12))

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
                .padding(horizontal = 28.dp)
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "pick a date",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(4.dp),
                )
            }

            // Year row — tap the year to open the full 1990–2050 list.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavArrow("◀") {
                    month = clampMonth(month.year, month.monthValue - 1)
                }
                Text(
                    text = "${month.year}",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, BonyColors.Rule)
                        .clickable { showYearList = true }
                        .padding(vertical = 8.dp),
                )
                NavArrow("▶") {
                    month = clampMonth(month.year, month.monthValue + 1)
                }
            }
            // Month row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavArrow("◀") {
                    month = clampMonth(month.year, month.monthValue - 1)
                }
                Text(
                    text = formatMonth(month),
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                NavArrow("▶") {
                    month = clampMonth(month.year, month.monthValue + 1)
                }
            }
            Spacer(Modifier.height(8.dp))

            MonthGrid(
                month = month,
                today = todayLocal(),
                selected = selected,
                eventDates = emptySet(),
                onDayClick = { selected = it },
                cellHeight = 52.dp,
            )

            Spacer(Modifier.height(12.dp))
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
                        .clickable { onSelect(selected) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }

    if (showYearList) {
        YearListOverlay(
            initialYear = month.year,
            onSelectYear = { year ->
                month = clampMonth(year, month.monthValue)
                showYearList = false
            },
            onDismiss = { showYearList = false },
        )
    }
}

@Composable
private fun NavArrow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.body.copy(color = BonyColors.TextMute),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Scrollable year list from 1990 to 2050, auto-scrolled to the current year. */
@Composable
private fun YearListOverlay(
    initialYear: Int,
    onSelectYear: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val state: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (initialYear - CALENDAR_START_YEAR).coerceAtLeast(0),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.9f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp)
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "year",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                items((CALENDAR_START_YEAR..CALENDAR_END_YEAR).toList(), key = { it }) { year ->
                    Text(
                        text = "$year",
                        style = BonyType.body.copy(
                            color = if (year == initialYear) BonyColors.Accent else BonyColors.Text,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectYear(year) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}
