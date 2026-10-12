package com.wordonline.matching.local.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wordonline.matching.local.domain.StaticDataTables
import com.wordonline.matching.local.domain.StaticTableSpec
import com.wordonline.matching.local.dto.StaticData
import com.wordonline.matching.local.dto.StaticTable
import com.wordonline.matching.local.repository.StaticDataRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds the static data of the offline pack and keeps the last build in memory. A scheduled
 * refresh rebuilds it; a request that arrives before the first build waits for one. A failed
 * rebuild keeps serving the previous build.
 */
@Service
class StaticDataService(
    private val repository: StaticDataRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error("[Offline Pack] static data refresh failed", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val mutex = Mutex()

    @Volatile
    private var cached: StaticData? = null

    /** The last build, building one first when there is none yet. */
    suspend fun current(): StaticData = cached ?: mutex.withLock { cached ?: build().also { cached = it } }

    /**
     * `fixedDelay` so a slow build never queues another behind it. The annotation needs a string,
     * so this one setting is read as a placeholder.
     */
    @Scheduled(fixedDelayString = "\${offline-pack.static-data-refresh-interval}")
    fun scheduleRefresh() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val built = build()
        mutex.withLock { cached = built }
    }

    suspend fun build(): StaticData {
        val columnsByTable = repository.findColumns(StaticDataTables.SPECS.map(StaticTableSpec::name))
        val tables = sortedMapOf<String, StaticTable>()
        for (spec in StaticDataTables.SPECS) {
            val existing = columnsByTable[spec.name]
            if (existing == null) {
                log.warn("[Offline Pack] table {} does not exist; left out of the static data", spec.name)
                continue
            }
            val columns = spec.columns
                ?.mapNotNull { name -> existing.firstOrNull { it.name == name } }
                ?: existing
            tables[spec.name] = StaticTable(columns.map { it.name }, repository.findRows(spec, columns))
        }
        return StaticData(
            schemaVersion = StaticDataTables.SCHEMA_VERSION,
            staticVersion = staticVersionOf(tables),
            generatedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS),
            tables = tables,
        )
    }

    companion object {
        // Its own mapper, so a change to the application's Jackson settings cannot change the hash
        // of unchanged data.
        private val hashMapper = jacksonObjectMapper()

        /** sha256 hex of [tables] serialized in table-name order; equal data gives an equal value. */
        fun staticVersionOf(tables: Map<String, StaticTable>): String {
            val bytes = hashMapper.writeValueAsBytes(tables.toSortedMap())
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }
}
