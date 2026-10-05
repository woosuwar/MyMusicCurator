package com.mymusiccurator.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mymusiccurator.MusicCuratorApp
import java.util.concurrent.TimeUnit

/** 새로 수집된 곡의 메타데이터(장르/BPM/분위기/연도)를 채운다 */
class EnrichmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MusicCuratorApp).container
        val (_, hadNetworkError) = container.enricher.enrichPending()
        return if (hadNetworkError && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/** 하루 한 번 취향 기반 추천을 갱신한다 */
class RecommendationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MusicCuratorApp).container
        container.enricher.enrichPending()
        container.recommendationEngine.refresh()
        return Result.success()
    }
}

object WorkScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueEnrichment(context: Context) {
        val request = OneTimeWorkRequestBuilder<EnrichmentWorker>()
            .setConstraints(network)
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // 이미 대기 중인 작업이 있으면 그 작업이 새 곡까지 처리한다
        WorkManager.getInstance(context).enqueueUniqueWork("enrichment", ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecommendationWorker>(1, TimeUnit.DAYS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("daily-recommendations", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
