package com.wordonline.matching.local

import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.domain.LocalSessionState
import com.wordonline.matching.local.domain.LocalSessionType
import com.wordonline.matching.local.domain.StaticDataTables
import com.wordonline.matching.local.dto.LocalResultCount
import com.wordonline.matching.local.dto.LocalResultRequest
import com.wordonline.matching.local.dto.LocalResultResponse
import com.wordonline.matching.local.dto.StaticData
import com.wordonline.matching.local.repository.LocalSessionRepository
import com.wordonline.matching.local.repository.LocalSessionStore
import com.wordonline.matching.local.repository.LocalSessionTransition
import com.wordonline.matching.local.repository.StaticDataRepository
import com.wordonline.matching.local.service.LocalResultReportResult
import com.wordonline.matching.local.service.LocalSessionService
import com.wordonline.matching.local.service.StaticDataService
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.matching.service.PracticeOpponentSelector
import com.wordonline.matching.support.MigratedPostgresDatabase
import io.r2dbc.spi.Closeable
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The offline pack's row filters and the writes of an accepted local result, against a Postgres built
 * from the WordOnlineDatabase migrations through V036 (`statistic_games.hosted_locally`,
 * `local_session_rejections`).
 *
 * Gated on `QUEST_IT_DATABASE_URL` like the other Postgres tests; [MigratedPostgresDatabase] builds the
 * database [DATABASE_NAME] from `QUEST_IT_MIGRATION_DIR`.
 *
 * ```
 * QUEST_IT_DATABASE_URL=r2dbc:postgresql://it:it@localhost:55471/it \
 * QUEST_IT_MIGRATION_DIR=/path/to/database/migration \
 *   ./gradlew test --tests '*LocalHostingPostgresIntegrationTest'
 * ```
 *
 * Redis is replaced by a mocked [LocalSessionStore]; its script has its own test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = MigratedPostgresDatabase.DATABASE_URL_VARIABLE, matches = ".+")
class LocalHostingPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var staticDataService: StaticDataService
    private lateinit var repository: LocalSessionRepository

    private val issuedAt = Instant.parse("2026-10-12T03:00:00Z")
    private val now = Instant.parse("2026-10-12T03:10:00Z")

    private var userId = 0L
    private var botId = 0L
    private var magicId = 0L
    private var scenarioId = 0L

    @BeforeAll
    fun buildDatabaseFromMigrations() = runBlocking<Unit> {
        connectionFactory = MigratedPostgresDatabase.build(DATABASE_NAME, requiredVersion = 36)
        databaseClient = DatabaseClient.create(connectionFactory)
        staticDataService = StaticDataService(StaticDataRepository(databaseClient))
        repository = LocalSessionRepository(databaseClient, TransactionalOperator.create(R2dbcTransactionManager(connectionFactory)))
    }

    @AfterAll
    fun disconnect() {
        (connectionFactory as? Closeable)?.let { closeable ->
            runBlocking { Mono.from(closeable.close()).awaitSingleOrNull() }
        }
    }

    /** A fresh real player and bot, each owning a deck, so every test sees rows that must not leak. */
    @BeforeEach
    fun createParticipants() = runBlocking<Unit> {
        userId = queryLong("INSERT INTO users (id) SELECT GREATEST(COALESCE(MAX(id), 0), 900000) + 1 FROM users RETURNING id")!!
        botId = queryLong("INSERT INTO users (id) SELECT LEAST(COALESCE(MIN(id), 0), -900000) - 1 FROM users RETURNING id")!!
        exec("INSERT INTO bot_personas (user_id, name, hospitality) VALUES ($botId, 'it-bot-$botId', TRUE)")
        magicId = queryLong("SELECT MIN(id) FROM magics")!!
        scenarioId = queryLong("SELECT MIN(id) FROM scenarios")!!
        for (owner in listOf(userId, botId)) {
            val deckId = queryLong("INSERT INTO decks (name, user_id) VALUES ('it', $owner) RETURNING id")!!
            exec("INSERT INTO deck_cards (deck_id, magic_id, count) VALUES ($deckId, $magicId, 15)")
            exec("UPDATE users SET selected_deck_id = $deckId WHERE id = $owner")
        }
    }

    private suspend fun exec(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun queryLong(sql: String): Long? =
        databaseClient.sql(sql).map { row, _ -> row.get(0, Number::class.java)?.toLong() }.one().awaitSingleOrNull()

    private suspend fun queryString(sql: String): String? =
        databaseClient.sql(sql).map { row, _ -> row.get(0)?.toString() }.one().awaitSingleOrNull()

    private fun StaticData.column(table: String, column: String): List<Any?> {
        val data = tables.getValue(table)
        val index = data.columns.indexOf(column)
        return data.rows.map { it[index] }
    }

    // Offline pack

    @Test
    fun `정적 데이터에 실제 사용자 행은 없고 봇 행과 봇 덱만 있다`() = runBlocking<Unit> {
        val data = staticDataService.build()

        assertThat(data.tables.keys).containsExactlyElementsOf(StaticDataTables.SPECS.map { it.name }.sorted())
        assertThat(data.column("users", "id").map { (it as Number).toLong() }).allMatch { it < 0 }.contains(botId)
        assertThat(data.tables.getValue("users").columns)
            .containsExactly("id", "selected_deck_id", "mmr", "status", "total_wins", "novice_progress")
        assertThat(data.column("decks", "user_id").map { (it as Number).toLong() }).allMatch { it < 0 }.contains(botId)
        val botDeckIds = data.column("decks", "id").map { (it as Number).toLong() }.toSet()
        assertThat(data.column("deck_cards", "deck_id").map { (it as Number).toLong() }).isNotEmpty.allMatch { it in botDeckIds }
        val userDeckId = queryLong("SELECT selected_deck_id FROM users WHERE id = $userId")!!
        assertThat(data.column("deck_cards", "deck_id")).doesNotContain(userDeckId)
        // Value formats of the contract: number, string, boolean or null.
        data.tables.values.flatMap { it.rows }.flatten().forEach { value ->
            assertThat(value == null || value is Number || value is String || value is Boolean).isTrue()
        }
    }

    @Test
    fun `같은 데이터의 staticVersion 은 같고 행이 바뀌면 달라진다`() = runBlocking<Unit> {
        val first = staticDataService.build()
        val second = staticDataService.build()
        exec("UPDATE bot_personas SET thinking_time_ms = thinking_time_ms + 1 WHERE user_id = $botId")
        val changed = staticDataService.build()

        assertThat(second.staticVersion).isEqualTo(first.staticVersion)
        assertThat(changed.staticVersion).isNotEqualTo(first.staticVersion)
    }

    @Test
    fun `실제 사용자 행이 바뀌어도 staticVersion 은 그대로다`() = runBlocking<Unit> {
        val first = staticDataService.build()
        exec("UPDATE users SET total_wins = total_wins + 1 WHERE id = $userId")

        assertThat(staticDataService.build().staticVersion).isEqualTo(first.staticVersion)
    }

    // Results

    private val store: LocalSessionStore = mock()
    private val userService: UserService = mock()

    private fun service() = LocalSessionService(
        LocalSessionProperties(),
        store,
        repository,
        mock<MatchingQueueRepository>(),
        PracticeOpponentSelector(mock(), userService),
        userService,
        staticDataService,
        Jackson2ObjectMapperBuilder.json().build(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private suspend fun givenSession(type: LocalSessionType, rightUserId: Long, hospitalityBot: Boolean): String {
        val sessionId = "local-it-$userId"
        val record = LocalSessionRecord(
            sessionId, userId, type, scenarioId.takeIf { type == LocalSessionType.PVE }, rightUserId, hospitalityBot, issuedAt,
            LocalSessionState.ISSUED,
        )
        whenever(store.find(sessionId)).thenReturn(record)
        whenever(store.transition(sessionId, LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .thenReturn(LocalSessionTransition.TRANSITIONED)
        whenever(store.incrementDailyAccepted(eq(userId), any())).thenReturn(1L)
        return sessionId
    }

    private fun result(winUserId: Long?, lossUserId: Long?, durationMillis: Long = 154_000) = LocalResultRequest(
        outcome = if (winUserId == null) "DRAW" else "WIN",
        winUserId = winUserId,
        lossUserId = lossUserId,
        durationMillis = durationMillis,
        frameCount = durationMillis * 60 / 1000,
        tickRate = 60,
        localServerVersion = "1.3.0",
        decks = listOf(LocalResultCount(userId, magicId, 15)),
        magics = listOf(LocalResultCount(userId, magicId, 4), LocalResultCount(rightUserId(winUserId, lossUserId), magicId, 2)),
    )

    private fun rightUserId(winUserId: Long?, lossUserId: Long?): Long =
        listOfNotNull(winUserId, lossUserId).firstOrNull { it != userId } ?: botId

    @Test
    fun `접대봇을 이긴 연습 경기는 로컬 표시와 함께 통계를 남기고 승수와 초보자 진행도를 올린다`() = runBlocking<Unit> {
        val sessionId = givenSession(LocalSessionType.PRACTICE, botId, hospitalityBot = true)

        val outcome = service().report(userId, sessionId, result(userId, botId))

        assertThat(outcome).isEqualTo(LocalResultReportResult.Decided(LocalResultResponse(true)))
        val gameId = queryLong("SELECT id FROM statistic_games WHERE win_user_id = $userId")!!
        assertThat(queryString("SELECT concat_ws('|', outcome, loss_user_id, duration, game_type, server_version, event_schema_version, hosted_locally::text) FROM statistic_games WHERE id = $gameId"))
            .isEqualTo("WIN|$botId|154|Practice|1.3.0|1|true")
        assertThat(queryString("SELECT string_agg(concat_ws(':', user_id, magic_id, count), ',') FROM statistic_game_decks WHERE statistic_game_id = $gameId"))
            .isEqualTo("$userId:$magicId:15")
        assertThat(queryLong("SELECT COUNT(*) FROM statistic_game_magics WHERE statistic_game_id = $gameId")).isEqualTo(2L)
        assertThat(queryLong("SELECT total_wins FROM users WHERE id = $userId")).isEqualTo(1L)
        assertThat(queryString("SELECT novice_progress::numeric FROM users WHERE id = $userId")).isEqualTo("0.65")
    }

    @Test
    fun `일반 봇을 이기면 승수만 오르고 초보자 진행도는 그대로다`() = runBlocking<Unit> {
        val sessionId = givenSession(LocalSessionType.PRACTICE, botId, hospitalityBot = false)

        service().report(userId, sessionId, result(userId, botId))

        assertThat(queryLong("SELECT total_wins FROM users WHERE id = $userId")).isEqualTo(1L)
        assertThat(queryString("SELECT novice_progress::numeric FROM users WHERE id = $userId")).isEqualTo("0.5")
    }

    @Test
    fun `PVE 승리는 ACTIVE 시나리오를 FINISHED 로 바꾼다`() = runBlocking<Unit> {
        exec("INSERT INTO user_scenarios (user_id, scenario_id, state) VALUES ($userId, $scenarioId, 'ACTIVE')")
        val sessionId = givenSession(LocalSessionType.PVE, -1L, hospitalityBot = false)

        service().report(userId, sessionId, result(userId, -1L))

        assertThat(queryString("SELECT state FROM user_scenarios WHERE user_id = $userId AND scenario_id = $scenarioId")).isEqualTo("FINISHED")
        assertThat(queryString("SELECT game_type FROM statistic_games WHERE win_user_id = $userId")).isEqualTo("PVE")
        assertThat(queryString("SELECT novice_progress::numeric FROM users WHERE id = $userId")).isEqualTo("0.5")
    }

    @Test
    fun `거절한 결과는 사유와 요청 JSON 을 local_session_rejections 에 남기고 통계는 쓰지 않는다`() = runBlocking<Unit> {
        val sessionId = givenSession(LocalSessionType.PRACTICE, botId, hospitalityBot = true)

        val outcome = service().report(userId, sessionId, result(userId, botId, durationMillis = 20_000))

        assertThat(outcome).isEqualTo(LocalResultReportResult.Decided(LocalResultResponse(false, "TOO_SHORT")))
        assertThat(queryString("SELECT concat_ws('|', session_id, reason, payload->>'outcome', payload->>'durationMillis') FROM local_session_rejections WHERE user_id = $userId"))
            .isEqualTo("$sessionId|TOO_SHORT|WIN|20000")
        assertThat(queryLong("SELECT COUNT(*) FROM statistic_games WHERE win_user_id = $userId")).isEqualTo(0L)
        assertThat(queryLong("SELECT total_wins FROM users WHERE id = $userId")).isEqualTo(0L)
    }

    @Test
    fun `발급에 쓰는 덱 보유 마법 초보자 진행도 조회가 실제 열을 읽는다`() = runBlocking<Unit> {
        exec("INSERT INTO user_magics (user_id, magic_id, count) VALUES ($userId, $magicId, 3)")

        assertThat(repository.findSelectedDeckCardIds(userId)).hasSize(15).containsOnly(magicId)
        assertThat(repository.findOwnedMagicIds(userId)).containsExactly(magicId)
        assertThat(repository.findNoviceProgress(userId)).isEqualTo(0.5)
        assertThat(repository.findScenarioState(userId, scenarioId)).isNull()
    }

    companion object {
        private const val DATABASE_NAME = "local_hosting_it"
    }
}
