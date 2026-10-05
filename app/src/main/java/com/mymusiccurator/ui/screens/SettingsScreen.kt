package com.mymusiccurator.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.mymusiccurator.appContainer
import com.mymusiccurator.service.MediaListenerService
import com.mymusiccurator.ui.components.KeyValueRow
import com.mymusiccurator.ui.components.SectionCard
import com.mymusiccurator.work.WorkScheduler

data class PermissionState(val notificationAccess: Boolean, val audioAccess: Boolean)

@Composable
fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    fun read() = PermissionState(
        notificationAccess = MediaListenerService.isEnabled(context),
        audioAccess = context.appContainer.localMetadata.hasPermission(),
    )
    var state by remember { mutableStateOf(read()) }
    // 설정 화면에서 돌아왔을 때 다시 확인
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = read() }
    return state
}

/** 대시보드 맨 위에 보이는 권한 안내 */
@Composable
fun SetupBanner(state: PermissionState, onOpenSettings: () -> Unit) {
    if (state.notificationAccess) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("재생 기록을 모으려면 알림 접근 권한이 필요해요", style = MaterialTheme.typography.titleSmall)
            Text(
                "다른 음악 앱의 '지금 재생 중' 정보를 읽기 위해 사용해요. 알림 내용은 저장하지 않아요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenSettings) { Text("설정 열기") }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val perms = rememberPermissionState()
    var audioGrantedNow by remember { mutableStateOf<Boolean?>(null) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGrantedNow = granted
        if (granted) WorkScheduler.enqueueEnrichment(context)
    }

    val excludedCount by container.repository.excludedSourcePlayCount.collectAsStateWithLifecycle(initialValue = 0)
    var confirmCleanup by remember { mutableStateOf(false) }
    var cleanupResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("YouTube 기록 삭제") },
            text = { Text("YouTube 앱에서 기록된 재생 ${excludedCount}회를 지우고, 기록이 남지 않은 곡도 목록에서 없앱니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    scope.launch {
                        val n = container.repository.deleteExcludedSourcePlays()
                        cleanupResult = "재생 기록 ${n}회를 삭제했어요"
                    }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("취소") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("권한") {
            KeyValueRow("알림 접근", if (perms.notificationAccess) "허용됨 ✓" else "필요")
            Text("모든 음악 앱의 재생 정보를 받기 위해 필수예요.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("알림 접근 설정 열기")
            }
            KeyValueRow("음악 파일", if (audioGrantedNow ?: perms.audioAccess) "허용됨 ✓" else "선택")
            Text("기기에 저장된 음악 파일의 장르·BPM·분위기 태그를 읽어요.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { audioLauncher.launch(container.localMetadata.audioPermission) }) {
                Text("음악 파일 접근 허용")
            }
        }
        SectionCard("기록 정리") {
            Text(
                "YouTube 앱은 음악이 아닌 동영상도 재생하기 때문에 기록하지 않아요. (YouTube Music 은 기록해요)",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValueRow("YouTube 기록", "${excludedCount}회")
            OutlinedButton(onClick = { confirmCleanup = true }, enabled = excludedCount > 0) {
                Text("YouTube 에서 쌓인 기록 삭제")
            }
            cleanupResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "그 밖에 잘못 인식된 곡은 곡 목록에서 길게 눌러 선택해 지우거나, 곡 정보 화면에서 삭제할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("외부 API") {
            KeyValueRow("Last.fm", if (container.lastFm.isConfigured) "설정됨 ✓" else "키 없음")
            KeyValueRow("MusicBrainz", "사용 (키 불필요)")
            KeyValueRow("GetSongBPM", if (container.getSongBpm.isConfigured) "설정됨 ✓" else "키 없음 (선택)")
            Text(
                "Last.fm: 장르/분위기 태그, 비슷한 가수 추천\nMusicBrainz: 원 발매연도\nGetSongBPM: 파일에 BPM 태그가 없을 때",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { WorkScheduler.enqueueEnrichment(context) }) { Text("지금 메타데이터 수집") }
        }
        SectionCard("데이터 출처") {
            AttributionLink("BPM 데이터 제공: GetSongBPM", "https://getsongbpm.com")
            AttributionLink("태그·추천 데이터: Last.fm", "https://www.last.fm")
            AttributionLink("발매연도 데이터: MusicBrainz", "https://musicbrainz.org")
        }
    }
}

@Composable
private fun AttributionLink(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { uriHandler.openUri(url) }.padding(vertical = 4.dp),
    )
}
