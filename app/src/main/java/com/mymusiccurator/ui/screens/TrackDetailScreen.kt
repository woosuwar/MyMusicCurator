package com.mymusiccurator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymusiccurator.ui.Format
import com.mymusiccurator.ui.TrackDetailViewModel
import com.mymusiccurator.ui.components.KeyValueRow
import com.mymusiccurator.ui.components.SectionCard

@Composable
fun TrackDetailScreen(vm: TrackDetailViewModel, onDeleted: () -> Unit) {
    val data by vm.track.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val cycle by vm.cycle.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val ts = data ?: return
    val t = ts.track
    val now = System.currentTimeMillis()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(t.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard("재생 통계") {
                val c = cycle
                KeyValueRow("재생 횟수", "${ts.playCount}회 (최근 7일 ${c?.playsLast7Days ?: 0} · 30일 ${c?.playsLast30Days ?: 0})")
                KeyValueRow("총 청취", Format.listenTime(ts.totalListenedMs))
                KeyValueRow("처음 재생", Format.dateTime(ts.firstPlayedAt))
                KeyValueRow("마지막 재생", "${Format.dateTime(ts.lastPlayedAt)} (${Format.ago(ts.lastPlayedAt)})")
                KeyValueRow("평균 주기", c?.averageIntervalMs?.let { "${Format.interval(it)}마다" })
                KeyValueRow("보통 주기", c?.medianIntervalMs?.let { "${Format.interval(it)}마다 (중앙값)" })
                KeyValueRow(
                    "다음 예상",
                    c?.nextExpectedAt?.let { if (c.isDue(now)) "지금 다시 들을 때예요!" else Format.dateTime(it) },
                )
            }
        }
        item {
            SectionCard("메타데이터") {
                KeyValueRow("앨범", t.album)
                KeyValueRow("장르", t.genre)
                KeyValueRow("BPM", t.bpm?.toString())
                KeyValueRow("분위기", t.mood)
                KeyValueRow("발매 연도", t.year?.toString())
                KeyValueRow("작곡가", t.composer)
                KeyValueRow("길이", if (t.durationMs > 0) "%d:%02d".format(t.durationMs / 60000, t.durationMs / 1000 % 60) else null)
                KeyValueRow("태그", t.tags)
                KeyValueRow("출처", t.metadataSource)
                KeyValueRow("상태", if (t.enrichedAt != null) "수집 완료 (${Format.ago(t.enrichedAt)})" else "수집 대기 중")
                OutlinedButton(onClick = vm::reanalyze) {
                    Icon(Icons.Default.Refresh, null)
                    Text("  메타데이터 다시 가져오기")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, null)
                    Text("  잘못 인식된 곡이면 삭제")
                }
            }
        }
        item { Text("재생 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(history.take(100)) { h ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Format.dateTime(h.startedAt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Format.listenTime(h.listenedMs).let { if (h.listenedMs < 60_000) "${h.listenedMs / 1000}초" else it }}${if (h.completed) " · 완청" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("곡 삭제") },
            text = { Text("'${t.title}' 과(와) 재생 기록 ${ts.playCount}회를 모두 지웁니다. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDeleted)
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}
