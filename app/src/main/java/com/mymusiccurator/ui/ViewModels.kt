package com.mymusiccurator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymusiccurator.appContainer
import com.mymusiccurator.data.db.PlayRow
import com.mymusiccurator.data.db.RecommendationEntity
import com.mymusiccurator.data.db.RecommendationType
import com.mymusiccurator.data.db.TrackWithStats
import com.mymusiccurator.domain.DashboardStats
import com.mymusiccurator.domain.PlayCycle
import com.mymusiccurator.domain.RecommendationEngine
import com.mymusiccurator.domain.StatsCalculator
import com.mymusiccurator.work.WorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAY = 24 * 60 * 60 * 1000L

enum class Period(val label: String, val days: Int?) {
    TODAY("오늘", null), WEEK("7일", 7), MONTH("30일", 30), YEAR("1년", 365), ALL("전체", null), CUSTOM("기간 선택", null)
}

/** 날짜 범위 – 시작일 00:00 부터 종료일 다음 날 00:00 전까지 */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    fun fromMillis(zone: ZoneId) = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun untilMillis(zone: ZoneId) = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val label: String
        get() {
            val f = DateTimeFormatter.ofPattern(if (start.year == end.year) "M.d" else "yyyy.M.d")
            return if (start == end) start.format(DateTimeFormatter.ofPattern("yyyy.M.d")) else "${start.format(f)} ~ ${end.format(f)}"
        }
}

data class DashboardState(val stats: DashboardStats, val recentPlays: List<PlayRow>, val rangeLabel: String)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    private val _period = MutableStateFlow(Period.TODAY)
    val period: StateFlow<Period> = _period.asStateFlow()

    private val _customRange = MutableStateFlow(LocalDate.now().let { DateRange(it.minusDays(6), it) })
    val customRange: StateFlow<DateRange> = _customRange.asStateFlow()

    val state: StateFlow<DashboardState?> = combine(_period, _customRange) { p, custom -> p to custom }
        .flatMapLatest { (p, custom) ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val (from, until, label) = when (p) {
                Period.TODAY -> Triple(DateRange(today, today).fromMillis(zone), Long.MAX_VALUE, "오늘 (${DateRange(today, today).label})")
                Period.CUSTOM -> Triple(custom.fromMillis(zone), custom.untilMillis(zone), custom.label)
                Period.ALL -> Triple(0L, Long.MAX_VALUE, "전체")
                else -> Triple(System.currentTimeMillis() - p.days!! * DAY, Long.MAX_VALUE, "최근 ${p.label}")
            }
            container.repository.playRowsBetween(from, until).map { rows ->
                DashboardState(StatsCalculator.dashboard(rows, zone), rows.asReversed().take(RECENT_LIMIT), label)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val nowPlaying = container.nowPlaying

    fun setPeriod(p: Period) {
        _period.value = p
    }

    fun setCustomRange(range: DateRange) {
        _customRange.value = range
        _period.value = Period.CUSTOM
    }

    companion object {
        const val RECENT_LIMIT = 200
    }
}

enum class TrackSort(val label: String) { PLAYS("많이 들은"), RECENT("최근"), CYCLE("자주 듣는 주기"), TITLE("제목") }

data class TrackListItem(val stats: TrackWithStats, val averageIntervalMs: Long?, val isDue: Boolean)

class TracksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.appContainer.repository

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(TrackSort.PLAYS)

    val items: StateFlow<List<TrackListItem>> = combine(repo.tracksWithStats, query, sort) { list, q, s ->
        val now = System.currentTimeMillis()
        list.asSequence()
            .filter { it.playCount > 0 }
            .filter { q.isBlank() || it.track.title.contains(q, true) || it.track.artist.contains(q, true) || it.track.album?.contains(q, true) == true || it.track.genre?.contains(q, true) == true }
            .map { ts ->
                val avg = Format.averageInterval(ts.playCount, ts.firstPlayedAt, ts.lastPlayedAt)
                TrackListItem(ts, avg, avg != null && ts.lastPlayedAt != null && now - ts.lastPlayedAt > avg * 1.5)
            }
            .let { seq ->
                when (s) {
                    TrackSort.PLAYS -> seq.sortedByDescending { it.stats.playCount }
                    TrackSort.RECENT -> seq.sortedByDescending { it.stats.lastPlayedAt ?: 0 }
                    TrackSort.CYCLE -> seq.sortedBy { it.averageIntervalMs ?: Long.MAX_VALUE }
                    TrackSort.TITLE -> seq.sortedBy { it.stats.track.title.lowercase() }
                }
            }
            .toList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 길게 눌러 선택한 곡 id */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected = _selected.asStateFlow()

    fun toggleSelection(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAllVisible() {
        _selected.value = items.value.map { it.stats.track.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selected.value
        _selected.value = emptySet()
        viewModelScope.launch { repo.deleteTracks(ids) }
    }
}

class TrackDetailViewModel(app: Application, private val trackId: Long) : AndroidViewModel(app) {
    private val container = app.appContainer

    val track = container.repository.trackWithStats(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = container.repository.history(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val cycle: StateFlow<PlayCycle?> = container.repository.history(trackId)
        .map { h -> StatsCalculator.cycle(h.map { it.startedAt }, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            container.repository.deleteTracks(listOf(trackId))
            onDeleted()
        }
    }

    fun reanalyze() {
        viewModelScope.launch {
            container.repository.resetEnrichment(trackId)
            WorkScheduler.enqueueEnrichment(getApplication())
        }
    }
}

data class TasteShare(val name: String, val percent: Int)

class RecommendViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    val lastFmConfigured = container.lastFm.isConfigured

    val artists = container.repository.recommendations
        .map { list -> list.filter { it.type == RecommendationType.ARTIST } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<RecommendationEntity>())
    val genres = container.repository.recommendations
        .map { list -> list.filter { it.type == RecommendationType.GENRE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<RecommendationEntity>())

    /** 내 취향: 최근 가중치를 반영한 가수/장르 비중 */
    val taste: StateFlow<Pair<List<TasteShare>, List<TasteShare>>> = container.repository.playRowsSince(0)
        .map { rows ->
            val a = StatsCalculator.affinity(rows, System.currentTimeMillis())
            a.artists.take(5).map { TasteShare(it.first, Math.round(it.second * 100).toInt()) } to
                a.genres.take(5).map { TasteShare(it.first, Math.round(it.second * 100).toInt()) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TasteShare>() to emptyList())

    /** 평소 주기보다 오래 안 들은 애청곡 */
    val rediscover: StateFlow<List<TrackListItem>> = container.repository.tracksWithStats
        .map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.playCount >= 3 }
                .mapNotNull { ts ->
                    val avg = Format.averageInterval(ts.playCount, ts.firstPlayedAt, ts.lastPlayedAt) ?: return@mapNotNull null
                    val last = ts.lastPlayedAt ?: return@mapNotNull null
                    if (now - last > avg * 1.5) TrackListItem(ts, avg, true) else null
                }
                .sortedByDescending { it.stats.playCount }
                .take(10)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _message.value = try {
                when (val r = container.recommendationEngine.refresh()) {
                    is RecommendationEngine.Outcome.Success -> "가수 ${r.artists}명, 장르 ${r.genres}개를 추천했어요"
                    RecommendationEngine.Outcome.NoApiKey -> "Last.fm API 키가 필요해요 (local.properties)"
                    RecommendationEngine.Outcome.NotEnoughData -> "재생 기록이 조금 더 쌓이면 추천할 수 있어요"
                    RecommendationEngine.Outcome.NoResults -> "추천을 가져오지 못했어요. 네트워크를 확인해 주세요"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "추천 갱신 실패: ${e.message}"
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
