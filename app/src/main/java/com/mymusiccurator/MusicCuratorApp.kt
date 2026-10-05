package com.mymusiccurator

import android.app.Application
import android.content.Context
import com.mymusiccurator.data.db.AppDatabase
import com.mymusiccurator.data.metadata.LocalMetadataReader
import com.mymusiccurator.data.remote.GetSongBpmClient
import com.mymusiccurator.data.remote.LastFmClient
import com.mymusiccurator.data.remote.MusicBrainzClient
import com.mymusiccurator.data.repository.MetadataEnricher
import com.mymusiccurator.data.repository.MusicRepository
import com.mymusiccurator.domain.RecommendationEngine
import com.mymusiccurator.domain.TrackMeta
import com.mymusiccurator.work.WorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow

class MusicCuratorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WorkScheduler.schedulePeriodic(this)
    }
}

/** 간단한 수동 DI 컨테이너 */
class AppContainer(context: Context) {
    val db = AppDatabase.create(context)
    val repository = MusicRepository(db)
    val localMetadata = LocalMetadataReader(context)
    val lastFm = LastFmClient()
    val getSongBpm = GetSongBpmClient()
    val enricher = MetadataEnricher(db.trackDao(), localMetadata, lastFm, MusicBrainzClient(), getSongBpm)
    val recommendationEngine = RecommendationEngine(db.trackDao(), db.playEventDao(), db.recommendationDao(), lastFm)

    /** 지금 재생 중인 곡 (서비스가 갱신, UI 가 구독) */
    val nowPlaying = MutableStateFlow<TrackMeta?>(null)
}

val Context.appContainer: AppContainer get() = (applicationContext as MusicCuratorApp).container
