package app.polyphony.core.downloads

import app.polyphony.core.domain.model.Track
import app.polyphony.core.domain.repository.CollectionDownloadState
import app.polyphony.core.domain.repository.DownloadRepository
import app.polyphony.core.domain.repository.DownloadRule
import app.polyphony.core.domain.repository.DownloadSettings
import app.polyphony.core.domain.repository.DownloadStatus
import app.polyphony.core.domain.repository.DownloadedTrack
import app.polyphony.core.domain.repository.LibrarySyncManager
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.StorageUsage
import app.polyphony.core.domain.repository.SyncState
import app.polyphony.core.downloads.db.DownloadDao
import app.polyphony.core.downloads.db.DownloadRuleDao
import app.polyphony.core.downloads.db.toDomain
import app.polyphony.core.downloads.db.toEntity
import app.polyphony.core.downloads.di.DownloadsScope
import app.polyphony.core.downloads.engine.DownloadEngine
import app.polyphony.core.downloads.engine.DownloadRuleEvaluator
import app.polyphony.core.downloads.storage.DownloadStorage
import app.polyphony.core.downloads.work.DownloadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val dao: DownloadDao,
    private val ruleDao: DownloadRuleDao,
    private val engine: DownloadEngine,
    private val evaluator: DownloadRuleEvaluator,
    private val storage: DownloadStorage,
    private val scheduler: DownloadScheduler,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val librarySyncManager: LibrarySyncManager,
    private val json: Json,
    @DownloadsScope private val scope: CoroutineScope,
) : DownloadRepository {

    private val activeServerId: Flow<String?> = serverRepository.activeServer.map { it?.id }.distinctUntilChanged()
    private val downloadSettings: Flow<DownloadSettings> = settingsRepository.settings.map { it.downloads }.distinctUntilChanged()

    /** Coalesced "please re-evaluate rules" requests. */
    private val evaluationTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    init {
        startObservers()
    }

    // ---- Downloads ----

    override val downloads: Flow<List<DownloadedTrack>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeForServer(id).map { rows -> rows.map { it.toDomain() } }
    }

    override fun downloadedTrackIds(): Flow<Set<String>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet()) else dao.observeCompletedIds(id).map { it.toSet() }
    }.distinctUntilChanged()

    override fun downloadState(trackId: String): Flow<DownloadedTrack?> =
        dao.observe(trackId).map { it?.toDomain() }.distinctUntilChanged()

    override fun collectionState(trackIds: List<String>): Flow<CollectionDownloadState> {
        val ids = trackIds.distinct()
        if (ids.isEmpty()) return flowOf(CollectionDownloadState(0, 0, 0, 0))
        val chunks = ids.chunked(DownloadEngine.CHUNK).map { chunk -> dao.observeByIds(chunk) }
        return combine(chunks) { lists -> lists.asList().flatten() }.map { rows ->
            CollectionDownloadState(
                total = ids.size,
                completed = rows.count { it.status == DownloadStatus.COMPLETED },
                inProgress = rows.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING },
                failed = rows.count { it.status == DownloadStatus.FAILED },
            )
        }.distinctUntilChanged()
    }

    override val storageUsage: Flow<StorageUsage> =
        combine(dao.observeUsedBytes(), dao.observeFileCount(), downloadSettings) { used, count, settings ->
            StorageUsage(
                usedBytes = used,
                limitBytes = settings.maxStorageMb * 1024L * 1024L,
                deviceFreeBytes = storage.freeBytes(settings.location),
                fileCount = count,
            )
        }.distinctUntilChanged()

    override suspend fun download(tracks: List<Track>, profileId: String?, pinned: Boolean, ruleId: String?) {
        engine.enqueue(tracks, profileId, pinned, ruleId)
    }

    override suspend fun remove(trackIds: List<String>) = engine.remove(trackIds)

    override suspend fun removeAll() {
        val serverId = activeServerId.first() ?: return
        engine.removeAllForServer(serverId)
    }

    override suspend fun pauseAll() = engine.pauseAll()
    override suspend fun resumeAll() = engine.resumeAll()
    override suspend fun retryFailed() = engine.retryFailed()
    override suspend fun localFile(trackId: String): DownloadedTrack? = engine.localFile(trackId)

    // ---- Rules ----

    override val rules: Flow<List<DownloadRule>> = activeServerId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else ruleDao.observeForServer(id).map { rows -> rows.mapNotNull { it.toDomain(json) } }
    }

    override suspend fun upsertRule(rule: DownloadRule) {
        ruleDao.upsert(rule.toEntity(json))
        // A rule edit should act promptly, not after the debounce window.
        scope.launch { runCatching { evaluator.evaluate(rule.serverId) } }
    }

    override suspend fun deleteRule(ruleId: String, removeFiles: Boolean) {
        val rule = ruleDao.get(ruleId)
        if (removeFiles) {
            val owned = dao.getForRule(ruleId).filter { !it.pinned }.map { it.trackId }
            engine.remove(owned)
        } else {
            dao.getForRule(ruleId).forEach { dao.setRule(it.trackId, null) }
        }
        ruleDao.delete(ruleId)
        rule?.let { r -> scope.launch { runCatching { evaluator.evaluate(r.serverId) } } }
    }

    override suspend fun evaluateRulesNow() {
        val serverId = activeServerId.first() ?: return
        evaluator.evaluate(serverId)
    }

    override val ruleEvaluationState: Flow<SyncState> = evaluator.state

    // ---- Triggers ----

    private fun startObservers() {
        // Library sync finished → rules may match new content.
        librarySyncManager.state
            .map { it.status }
            .distinctUntilChanged()
            .runningFold(Pair<SyncState.Status?, SyncState.Status?>(null, null)) { acc, s -> acc.second to s }
            .onEach { (prev, now) ->
                if (prev == SyncState.Status.RUNNING && now == SyncState.Status.IDLE) evaluationTrigger.tryEmit(Unit)
            }
            .launchIn(scope)

        // Rules table changed (any server) → re-evaluate.
        ruleDao.observeAll().drop(1).onEach { evaluationTrigger.tryEmit(Unit) }.launchIn(scope)

        // Active server changed → that server's rules apply now.
        activeServerId.drop(1).onEach { evaluationTrigger.tryEmit(Unit) }.launchIn(scope)

        // Settings → constraints, periodic schedule, and a re-evaluation (storage cap may have changed).
        downloadSettings
            .runningFold(Pair<DownloadSettings?, DownloadSettings?>(null, null)) { acc, s -> acc.second to s }
            .onEach { (prev, now) ->
                if (now == null) return@onEach
                val constraintsChanged = prev != null && (prev.wifiOnly != now.wifiOnly || prev.chargingOnly != now.chargingOnly)
                runCatching { engine.ensureDrainScheduled(now, replace = constraintsChanged) }
                if (prev == null || prev.ruleEvaluationHours != now.ruleEvaluationHours) {
                    scheduler.schedulePeriodicRuleEvaluation(now.ruleEvaluationHours)
                }
                if (prev != null) evaluationTrigger.tryEmit(Unit)
            }
            .launchIn(scope)

        evaluationTrigger
            .debounce(EVALUATION_DEBOUNCE_MS)
            .onEach {
                val serverId = activeServerId.first() ?: return@onEach
                runCatching { evaluator.evaluate(serverId) }
            }
            .launchIn(scope)
    }

    companion object {
        const val EVALUATION_DEBOUNCE_MS = 30_000L
    }
}
