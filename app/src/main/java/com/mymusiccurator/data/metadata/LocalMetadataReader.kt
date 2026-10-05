package com.mymusiccurator.data.metadata

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlin.math.abs

/** 기기에 저장된 음악 파일을 MediaStore 에서 찾아 태그를 읽는다. */
class LocalMetadataReader(private val context: Context) {

    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    /** 제목/가수/길이로 가장 그럴듯한 로컬 파일을 찾는다. */
    fun findLocalAudio(title: String, artist: String, durationMs: Long): Uri? {
        if (!hasPermission()) return null
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE",
                arrayOf(title),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                var best: Pair<Long, Int>? = null
                while (c.moveToNext()) {
                    var score = 0
                    val a = c.getString(artistCol).orEmpty()
                    if (a.equals(artist, ignoreCase = true)) score += 2
                    else if (a.contains(artist, true) || artist.contains(a, true)) score += 1
                    if (durationMs > 0 && abs(c.getLong(durCol) - durationMs) < 3_000) score += 2
                    if (score >= 2 && (best == null || score > best.second)) best = c.getLong(idCol) to score
                }
                best?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.first) }
            }
        }.onFailure { Log.w(TAG, "MediaStore 조회 실패", it) }.getOrNull()
    }

    fun read(uri: Uri): FileTags {
        val fromRetriever = runCatching { readWithRetriever(uri) }.getOrDefault(FileTags())
        val fromTags = runCatching {
            context.contentResolver.openInputStream(uri)?.use { AudioTagReader.read(it.buffered()) }
        }.onFailure { Log.w(TAG, "태그 파싱 실패: $uri", it) }.getOrNull()

        return FileTags(
            title = fromTags?.title ?: fromRetriever.title,
            artist = fromTags?.artist ?: fromRetriever.artist,
            album = fromTags?.album ?: fromRetriever.album,
            genre = fromTags?.genre ?: fromRetriever.genre,
            bpm = fromTags?.bpm,
            mood = fromTags?.mood?.let(TagClassifier::normalizeMood),
            year = fromTags?.year ?: fromRetriever.year,
            composer = fromTags?.composer ?: fromRetriever.composer,
        )
    }

    private fun readWithRetriever(uri: Uri): FileTags {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun get(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            return FileTags(
                title = get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let(Id3Genres::normalize),
                year = AudioTagReader.parseYear(
                    get(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: get(MediaMetadataRetriever.METADATA_KEY_DATE),
                ),
                composer = get(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            )
        } finally {
            r.release()
        }
    }

    private companion object {
        const val TAG = "LocalMetadataReader"
    }
}
