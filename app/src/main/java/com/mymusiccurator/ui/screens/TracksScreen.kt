package com.mymusiccurator.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mymusiccurator.ui.Format
import com.mymusiccurator.ui.TrackListItem
import com.mymusiccurator.ui.TrackSort
import com.mymusiccurator.ui.TracksViewModel
import com.mymusiccurator.ui.components.EmptyHint

@Composable
fun TracksScreen(onTrackClick: (Long) -> Unit, vm: TracksViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    BackHandler(enabled = selecting) { vm.clearSelection() }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onSelectAll = vm::selectAllVisible,
                onDelete = { confirmDelete = true },
                onCancel = vm::clearSelection,
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("제목, 가수, 앨범, 장르 검색") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackSort.entries.forEach { s ->
                    FilterChip(selected = s == sort, onClick = { vm.sort.value = s }, label = { Text(s.label) })
                }
            }
        }
        if (items.isEmpty()) {
            Column(Modifier.padding(16.dp)) { EmptyHint("아직 기록된 곡이 없어요. 음악 앱에서 노래를 재생해 보세요.") }
        } else if (!selecting) {
            Text(
                "곡을 길게 누르면 선택해서 지울 수 있어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn {
            items(items, key = { it.stats.track.id }) { item ->
                val id = item.stats.track.id
                TrackRow(
                    item = item,
                    selecting = selecting,
                    isSelected = id in selected,
                    onClick = { if (selecting) vm.toggleSelection(id) else onTrackClick(id) },
                    onLongClick = { vm.toggleSelection(id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("${selected.size}곡 삭제") },
            text = { Text("선택한 곡과 재생 기록을 모두 지웁니다. 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onSelectAll: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "선택 취소") }
            Text("${count}곡 선택됨", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "모두 선택") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "삭제", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    item: TrackListItem,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val t = item.stats.track
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (isSelected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = if (selecting) {
            { Checkbox(checked = isSelected, onCheckedChange = { onClick() }) }
        } else {
            null
        },
        headlineContent = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(t.artist, t.genre, t.year?.toString(), t.bpm?.let { "$it BPM" }).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "마지막 ${Format.ago(item.stats.lastPlayedAt)} · 평균 ${Format.interval(item.averageIntervalMs)}마다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Column {
                Text("${item.stats.playCount}회", style = MaterialTheme.typography.titleMedium)
                if (item.isDue && !selecting) AssistChip(onClick = onClick, label = { Text("다시 들을 때") })
            }
        },
    )
}
