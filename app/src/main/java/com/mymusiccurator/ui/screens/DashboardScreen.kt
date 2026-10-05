package com.mymusiccurator.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mymusiccurator.data.db.PlayRow
import com.mymusiccurator.domain.TrackMeta
import com.mymusiccurator.ui.DashboardViewModel
import com.mymusiccurator.ui.DateRange
import com.mymusiccurator.ui.Format
import com.mymusiccurator.ui.Period
import com.mymusiccurator.ui.components.ColumnChart
import com.mymusiccurator.ui.components.EmptyHint
import com.mymusiccurator.ui.components.RankedBars
import com.mymusiccurator.ui.components.SectionCard
import com.mymusiccurator.ui.components.StatTile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PREVIEW_COUNT = 10

@Composable
fun DashboardScreen(
    onTrackClick: (Long) -> Unit,
    setupBanner: @Composable () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val customRange by vm.customRange.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showAllPlays by remember(period, customRange) { mutableStateOf(false) }

    if (showPicker) {
        DateRangePickerDialog(
            initial = customRange,
            onDismiss = { showPicker = false },
            onConfirm = {
                showPicker = false
                vm.setCustomRange(it)
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { setupBanner() }
        nowPlaying?.let { np -> item { NowPlayingCard(np) } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p ->
                    FilterChip(
                        selected = p == period,
                        onClick = { if (p == Period.CUSTOM) showPicker = true else vm.setPeriod(p) },
                        label = { Text(if (p == Period.CUSTOM && period == Period.CUSTOM) customRange.label else p.label) },
                        leadingIcon = if (p == Period.CUSTOM) {
                            { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        val st = state ?: return@LazyColumn
        val s = st.stats
        item {
            Text(st.rangeLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("재생", "${s.totalPlays}회", Modifier.weight(1f))
                StatTile("청취 시간", Format.listenTime(s.totalListenMs), Modifier.weight(1f))
                StatTile("곡 / 가수", "${s.uniqueTracks} / ${s.uniqueArtists}", Modifier.weight(1f))
            }
        }
        item {
            SectionCard("재생 기록") {
                PlayHistoryList(
                    plays = if (showAllPlays) st.recentPlays else st.recentPlays.take(PREVIEW_COUNT),
                    onTrackClick = onTrackClick,
                )
                val hidden = st.recentPlays.size - PREVIEW_COUNT
                if (!showAllPlays && hidden > 0) {
                    TextButton(onClick = { showAllPlays = true }) { Text("${hidden}개 더 보기") }
                }
                if (showAllPlays && s.totalPlays > st.recentPlays.size) {
                    EmptyHint("최근 ${st.recentPlays.size}개까지 보여줘요. 기간을 좁혀 보세요.")
                }
            }
        }
        item {
            SectionCard("많이 들은 곡") {
                RankedBars(s.topTracks, onClick = { r -> r.id?.let(onTrackClick) }, emptyText = "음악을 30초 이상 재생하면 여기에 기록돼요")
            }
        }
        item { SectionCard("좋아하는 가수") { RankedBars(s.topArtists) } }
        item { SectionCard("장르") { RankedBars(s.topGenres, emptyText = "장르 정보를 수집 중이에요") } }
        item { SectionCard("분위기") { RankedBars(s.moods, emptyText = "분위기 정보가 없어요 (파일 MOOD 태그 또는 Last.fm 태그)") } }
        item { SectionCard("BPM 분포") { RankedBars(s.bpmBuckets, emptyText = "BPM 정보가 없어요 (파일 BPM 태그 또는 GetSongBPM)") } }
        item { SectionCard("발매 시기") { RankedBars(s.decades, emptyText = "발매연도 정보를 수집 중이에요") } }
        item {
            SectionCard("시간대별 재생") {
                ColumnChart(s.byHour, (0..23).map { "$it" }, labelEvery = 3)
            }
        }
        item {
            SectionCard("요일별 재생") {
                ColumnChart(s.byWeekday, listOf("월", "화", "수", "목", "금", "토", "일"))
            }
        }
    }
}

/** 재생 기록 목록 – 날짜가 바뀔 때마다 날짜 머리글을 넣는다 */
@Composable
private fun PlayHistoryList(plays: List<PlayRow>, onTrackClick: (Long) -> Unit) {
    if (plays.isEmpty()) {
        EmptyHint("이 기간에 재생한 곡이 없어요")
        return
    }
    val zone = ZoneId.systemDefault()
    val dateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    var lastDate: LocalDate? = null
    plays.forEach { p ->
        val t = Instant.ofEpochMilli(p.startedAt).atZone(zone)
        if (t.toLocalDate() != lastDate) {
            lastDate = t.toLocalDate()
            Text(
                t.format(dateFmt),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { onTrackClick(p.trackId) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                t.format(timeFmt),
                Modifier.width(52.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(p.artist, p.genre).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.completed) {
                Text("완청", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(initial: DateRange, onDismiss: () -> Unit, onConfirm: (DateRange) -> Unit) {
    // DatePicker 는 UTC 자정 기준 millis 를 쓴다
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.utcDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val todayMillis = remember { LocalDate.now().utcMillis() }

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start.utcMillis(),
        initialSelectedEndDateMillis = initial.end.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis!!.utcDate()
                    val end = pickerState.selectedEndDateMillis?.utcDate() ?: start
                    onConfirm(DateRange(start, end))
                },
            ) { Text("검색") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        DateRangePicker(
            state = pickerState,
            title = { Text("기간 선택", Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NowPlayingCard(meta: TrackMeta) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, contentDescription = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text("지금 재생 중", style = MaterialTheme.typography.labelMedium)
                Text(meta.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(meta.artist, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
