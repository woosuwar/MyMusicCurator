package com.mymusiccurator.domain

/** 음악 앱의 MediaSession 에서 받은 곡 정보 */
data class TrackMeta(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val genre: String? = null,
    val year: Int? = null,
) {
    val key: String get() = trackKey(artist, title)
}

/** 한 번의 유효한 재생 */
data class PlayRecord(
    val meta: TrackMeta,
    val sourcePackage: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)

fun trackKey(artist: String, title: String): String {
    fun norm(s: String) = s.lowercase()
        .replace(Regex("[(\\[](feat|ft|with)\\.?\\s[^)\\]]*[)\\]]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return "${norm(artist)}|${norm(title)}"
}

/**
 * 세션별로 "실제로 재생 중이었던 시간" 을 누적해서, 곡이 바뀌거나 멈추거나 반복될 때
 * 스크로블 규칙(30초 이상 + 곡 길이의 절반 또는 4분 이상)을 만족하면 [onPlay] 로 내보낸다.
 *
 * 모든 메서드는 한 스레드(메인 스레드)에서만 호출된다고 가정한다.
 */
class PlaybackTracker(
    private val onPlay: (PlayRecord) -> Unit,
    private val onNowPlaying: (TrackMeta?) -> Unit = {},
) {
    private class Session(
        val pkg: String,
        var meta: TrackMeta,
        val startedAt: Long,
        var accumulatedMs: Long = 0,
        var playingSince: Long? = null,
        var lastPositionMs: Long = -1,
        var lastPositionAt: Long = 0,
    ) {
        fun listened(now: Long) = accumulatedMs + (playingSince?.let { now - it } ?: 0)
        fun pause(now: Long) {
            playingSince?.let { accumulatedMs += now - it }
            playingSince = null
        }
    }

    private val sessions = mutableMapOf<String, Session>()
    private val playing = mutableMapOf<String, Boolean>()
    /** 세션이 생기기 전에 받은 마지막 재생 위치 (positionMs, at) */
    private val lastPosition = mutableMapOf<String, Pair<Long, Long>>()

    fun onMetadataChanged(sessionKey: String, pkg: String, meta: TrackMeta?, now: Long) {
        val current = sessions[sessionKey]
        if (meta != null && current != null && current.meta.key == meta.key) {
            // 같은 곡의 메타데이터 갱신(앨범아트 등) – 길이 정보만 보강
            if (current.meta.durationMs <= 0 && meta.durationMs > 0) current.meta = meta
            return
        }
        finish(sessionKey, now)
        if (meta == null || !isTrackable(meta)) {
            publishNowPlaying()
            return
        }
        val isPlaying = playing[sessionKey] == true
        sessions[sessionKey] = Session(pkg, meta, startedAt = now, playingSince = if (isPlaying) now else null).apply {
            lastPosition[sessionKey]?.let { (pos, at) -> lastPositionMs = pos; lastPositionAt = at }
        }
        publishNowPlaying()
    }

    fun onPlaybackStateChanged(sessionKey: String, isPlaying: Boolean, positionMs: Long, now: Long) {
        playing[sessionKey] = isPlaying
        if (positionMs >= 0) lastPosition[sessionKey] = positionMs to now
        val s = sessions[sessionKey] ?: return

        // 한 곡 반복: 위치가 처음으로 돌아왔고 이미 충분히 들었으면 새 재생으로 끊는다
        if (positionMs in 0..5_000 && s.lastPositionMs >= 0) {
            val estimated = s.lastPositionMs + if (s.playingSince != null) now - s.lastPositionAt else 0
            if (estimated > 30_000 && qualifies(s.listened(now), s.meta.durationMs)) {
                finish(sessionKey, now)
                sessions[sessionKey] = Session(s.pkg, s.meta, startedAt = now, playingSince = if (isPlaying) now else null)
                    .apply { lastPositionMs = positionMs; lastPositionAt = now }
                publishNowPlaying()
                return
            }
        }

        if (isPlaying) {
            if (s.playingSince == null) s.playingSince = now
        } else {
            s.pause(now)
        }
        if (positionMs >= 0) {
            s.lastPositionMs = positionMs
            s.lastPositionAt = now
        }
        publishNowPlaying()
    }

    fun onSessionDestroyed(sessionKey: String, now: Long) {
        finish(sessionKey, now)
        playing.remove(sessionKey)
        lastPosition.remove(sessionKey)
        publishNowPlaying()
    }

    fun flushAll(now: Long) {
        sessions.keys.toList().forEach { finish(it, now) }
        playing.clear()
        lastPosition.clear()
        publishNowPlaying()
    }

    private fun finish(sessionKey: String, now: Long) {
        val s = sessions.remove(sessionKey) ?: return
        val listened = s.listened(now)
        if (qualifies(listened, s.meta.durationMs)) {
            onPlay(
                PlayRecord(
                    meta = s.meta,
                    sourcePackage = s.pkg,
                    startedAt = s.startedAt,
                    endedAt = now,
                    listenedMs = listened,
                    completed = s.meta.durationMs > 0 && listened >= s.meta.durationMs * 0.9,
                ),
            )
        }
    }

    private fun publishNowPlaying() {
        val active = sessions.entries.firstOrNull { playing[it.key] == true }?.value
        onNowPlaying(active?.meta)
    }

    companion object {
        const val MIN_LISTEN_MS = 30_000L
        const val ENOUGH_LISTEN_MS = 4 * 60_000L

        fun qualifies(listenedMs: Long, durationMs: Long): Boolean {
            if (listenedMs < MIN_LISTEN_MS) return false
            if (durationMs <= 0) return true
            return listenedMs >= minOf(durationMs / 2, ENOUGH_LISTEN_MS)
        }

        fun isTrackable(meta: TrackMeta): Boolean =
            meta.title.isNotBlank() && meta.artist.isNotBlank() &&
                (meta.durationMs <= 0 || meta.durationMs >= MIN_LISTEN_MS)
    }
}
