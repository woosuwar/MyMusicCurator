package com.mymusiccurator.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mymusiccurator.data.db.RecommendationEntity
import com.mymusiccurator.domain.Ranked
import com.mymusiccurator.ui.Format
import com.mymusiccurator.ui.RecommendViewModel
import com.mymusiccurator.ui.components.EmptyHint
import com.mymusiccurator.ui.components.RankedBars
import com.mymusiccurator.ui.components.SectionCard

@Composable
fun RecommendScreen(onTrackClick: (Long) -> Unit, vm: RecommendViewModel = viewModel()) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val taste by vm.taste.collectAsStateWithLifecycle()
    val rediscover by vm.rediscover.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("내 취향") {
                    Text("최근에 들은 곡일수록 더 크게 반영돼요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("가수", style = MaterialTheme.typography.labelLarge)
                    RankedBars(taste.first.map { Ranked(it.name, it.percent, "${it.percent}%") })
                    Text("장르", style = MaterialTheme.typography.labelLarge)
                    RankedBars(taste.second.map { Ranked(it.name, it.percent, "${it.percent}%") }, emptyText = "장르 정보를 수집 중이에요")
                }
            }
            item {
                Button(onClick = vm::refresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AutoAwesome, null)
                    }
                    Text("  추천 새로 받기")
                }
                if (!vm.lastFmConfigured) {
                    Text(
                        "외부 추천을 받으려면 local.properties 에 LASTFM_API_KEY 를 넣고 다시 빌드하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                SectionCard("추천 가수") {
                    if (artists.isEmpty()) EmptyHint("‘추천 새로 받기’를 눌러 보세요")
                    artists.forEach { rec ->
                        RecommendationRow(rec) {
                            val url = rec.url ?: "https://www.last.fm/music/${Uri.encode(rec.name)}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                }
            }
            item {
                SectionCard("새로 들어볼 장르") {
                    if (genres.isEmpty()) EmptyHint("추천 가수를 바탕으로 계산돼요")
                    genres.forEach { rec ->
                        RecommendationRow(rec) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.last.fm/tag/${Uri.encode(rec.name.lowercase())}")))
                        }
                    }
                }
            }
            item {
                SectionCard("다시 들을 때가 된 애청곡") {
                    Text("평소 듣던 주기보다 오래 안 들은 곡이에요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (rediscover.isEmpty()) EmptyHint("아직 없어요")
                    rediscover.forEach { item ->
                        val t = item.stats.track
                        Column(Modifier.fillMaxWidth().clickable { onTrackClick(t.id) }.padding(vertical = 4.dp)) {
                            Text("${t.title} — ${t.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${item.stats.playCount}회 · 평균 ${Format.interval(item.averageIntervalMs)}마다 · 마지막 ${Format.ago(item.stats.lastPlayedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun RecommendationRow(rec: RecommendationEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rec.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(rec.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SuggestionChip(onClick = onClick, label = { Text("%.0f".format(rec.score * 100)) })
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 8.dp).size(18.dp))
    }
}
