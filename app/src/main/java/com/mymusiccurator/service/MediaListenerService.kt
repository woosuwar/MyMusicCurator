package com.mymusiccurator.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import com.mymusiccurator.appContainer
import com.mymusiccurator.data.metadata.AudioTagReader
import com.mymusiccurator.domain.PlayRecord
import com.mymusiccurator.domain.PlaybackTracker
import com.mymusiccurator.domain.SourceFilter
import com.mymusiccurator.domain.TrackMeta
import com.mymusiccurator.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 알림 접근 권한으로 동작하는 서비스. 이 권한이 있어야 다른 음악 앱(유튜브 뮤직, 스포티파이,
 * 멜론, 삼성 뮤직 등)의 MediaSession 을 읽을 수 있다.
 */
class MediaListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sessionManager: MediaSessionManager? = null

    private val tracker by lazy {
        PlaybackTracker(
            onPlay = ::save,
            onNowPlaying = { appContainer.nowPlaying.value = it },
        )
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        syncControllers(list.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = manager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            syncControllers(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 접근 권한이 없습니다", e)
        }
    }

    override fun onListenerDisconnected() {
        teardown()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        controllers.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        tracker.flushAll(System.currentTimeMillis())
    }

    private fun syncControllers(active: List<MediaController>) {
        val now = System.currentTimeMillis()
        val activeTokens = active.map { it.sessionToken }.toSet()

        // 사라진 세션 정리
        controllers.keys.filter { it !in activeTokens }.forEach { token ->
            controllers.remove(token)?.let { (c, cb) ->
                c.unregisterCallback(cb)
                tracker.onSessionDestroyed(keyOf(c), now)
            }
        }

        // 새 세션 등록
        active.filter {
            it.sessionToken !in controllers && it.packageName != packageName && !SourceFilter.isExcluded(it.packageName)
        }.forEach { c ->
            val key = keyOf(c)
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    tracker.onMetadataChanged(key, c.packageName, metadata?.toTrackMeta(), System.currentTimeMillis())
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    tracker.onPlaybackStateChanged(key, state.isPlaying(), state?.position ?: -1, System.currentTimeMillis())
                }

                override fun onSessionDestroyed() {
                    tracker.onSessionDestroyed(key, System.currentTimeMillis())
                }
            }
            c.registerCallback(callback, handler)
            controllers[c.sessionToken] = c to callback

            // 이미 재생 중인 곡 반영
            tracker.onPlaybackStateChanged(key, c.playbackState.isPlaying(), c.playbackState?.position ?: -1, now)
            tracker.onMetadataChanged(key, c.packageName, c.metadata?.toTrackMeta(), now)
        }
    }

    private fun save(record: PlayRecord) {
        val container = appContainer
        scope.launch {
            runCatching { container.repository.recordPlay(record) }
                .onSuccess { needsEnrichment -> if (needsEnrichment) WorkScheduler.enqueueEnrichment(applicationContext) }
                .onFailure { Log.e(TAG, "재생 기록 저장 실패", it) }
        }
    }

    private fun keyOf(c: MediaController) = "${c.packageName}#${c.sessionToken.hashCode()}"

    private fun PlaybackState?.isPlaying() = this?.state == PlaybackState.STATE_PLAYING

    private fun MediaMetadata.toTrackMeta(): TrackMeta? {
        val title = getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
        val artist = (getString(MediaMetadata.METADATA_KEY_ARTIST) ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim()
        if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return null
        return TrackMeta(
            title = title,
            artist = artist,
            album = getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION),
            genre = getString(MediaMetadata.METADATA_KEY_GENRE),
            year = getLong(MediaMetadata.METADATA_KEY_YEAR).takeIf { it in 1800..2100 }?.toInt()
                ?: AudioTagReader.parseYear(getString(MediaMetadata.METADATA_KEY_DATE)),
        )
    }

    companion object {
        private const val TAG = "MediaListenerService"

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, MediaListenerService::class.java)
            return flat.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == me }
        }
    }
}
