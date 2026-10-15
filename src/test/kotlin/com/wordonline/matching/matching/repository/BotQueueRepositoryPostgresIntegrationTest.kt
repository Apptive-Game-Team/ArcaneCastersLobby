package com.wordonline.matching.matching.repository

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory
import org.springframework.r2dbc.core.DatabaseClient

/**
 * Runs [BotQueueRepository] and [BotPersonaRepository.findRandomEnabled] against a real Postgres,
 * because the matchable-persona rule compares `llm_api_keys.unavailable_until` with
 * `CURRENT_TIMESTAMP` in SQL and a mock cannot show that.
 *
 * Skipped unless `QUEST_IT_DATABASE_URL` is set. Point it at an EMPTY scratch database: the test
 * drops and recreates stub `servers`, `users`, `bot_personas` and `llm_api_keys` tables carrying
 * the columns of WordOnlineDatabase V044 and V045.
 *
 * ```
 * docker run -d --name lobby-bot-queue-it-pg -e POSTGRES_USER=t -e POSTGRES_PASSWORD=t -e POSTGRES_DB=t \
 *   -p 55466:5432 postgres:16-alpine
 * QUEST_IT_DATABASE_URL=r2dbc:postgresql://t:t@localhost:55466/t \
 *   ./gradlew test --tests '*BotQueueRepositoryPostgresIntegrationTest'
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class BotQueueRepositoryPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var botQueueRepository: BotQueueRepository
    private lateinit var botPersonaRepository: BotPersonaRepository

    @BeforeAll
    fun connect() {
        connectionFactory = ConnectionFactories.get(System.getenv("QUEST_IT_DATABASE_URL"))
        databaseClient = DatabaseClient.create(connectionFactory)
        botQueueRepository = BotQueueRepository(databaseClient)
        val repositoryFactory = R2dbcRepositoryFactory(R2dbcEntityTemplate(databaseClient, PostgresDialect.INSTANCE))
        botPersonaRepository = repositoryFactory.getRepository(BotPersonaRepository::class.java)
    }

    @AfterAll
    fun disconnect() {
        (connectionFactory as? io.r2dbc.spi.Closeable)?.let { closeable ->
            runBlocking { reactor.core.publisher.Mono.from(closeable.close()).awaitSingleOrNull() }
        }
    }

    @BeforeEach
    fun recreateSchema() = runBlocking<Unit> {
        SCHEMA.split(";").map(String::trim).filter(String::isNotEmpty).forEach { execute(it) }
    }

    private suspend fun execute(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    @Test
    @DisplayName("ACTIVE 게임 서버만 세고 target_bot_sessions 가 NULL 이면 기본값을 쓴다")
    fun activeGameServerTargets() = runBlocking<Unit> {
        execute(
            """
            INSERT INTO servers(id, type, state, target_bot_sessions) VALUES
              (1, 'GAME', 'ACTIVE', NULL),
              (2, 'GAME', 'ACTIVE', 3),
              (3, 'GAME', 'INACTIVE', 5),
              (4, 'LOBBY', 'ACTIVE', 7)
            """,
        )

        assertThat(botQueueRepository.findActiveGameServerTargets(2))
            .containsExactly(GameServerBotTarget(1L, 2), GameServerBotTarget(2L, 3))
    }

    @Test
    @DisplayName("RULE 봇과 key 가 쓸 수 있는 LLM 봇만 실제 MMR 과 함께 고른다")
    fun matchableBotsHonourUnavailableUntil() = runBlocking<Unit> {
        execute(
            """
            INSERT INTO llm_api_keys(provider, encrypted_key, key_hint, unavailable_until) VALUES
              ('JEV', 'v1:x', '1234', CURRENT_TIMESTAMP + INTERVAL '1 hour'),
              ('OPENROUTER', 'v1:y', '5678', CURRENT_TIMESTAMP - INTERVAL '1 minute')
            """,
        )
        insertBots()

        val matchable = botQueueRepository.findMatchableBots()

        assertThat(matchable).containsExactlyInAnyOrder(MatchableBot(-1L, 1100L), MatchableBot(-3L, 1300L))
    }

    @Test
    @DisplayName("provider key 행이 없는 LLM 봇은 고르지 않고 unavailable_until 이 NULL 이면 고른다")
    fun missingKeyRowExcludes() = runBlocking<Unit> {
        execute("INSERT INTO llm_api_keys(provider, encrypted_key, key_hint) VALUES ('JEV', 'v1:x', '1234')")
        insertBots()

        assertThat(botQueueRepository.findMatchableBots().map { it.userId }).containsExactlyInAnyOrder(-1L, -2L)
    }

    @Test
    @DisplayName("연습 상대 조회도 같은 규칙을 따른다")
    fun practiceOpponentUsesSameRule() = runBlocking<Unit> {
        execute(
            """
            INSERT INTO llm_api_keys(provider, encrypted_key, key_hint, unavailable_until) VALUES
              ('JEV', 'v1:x', '1234', CURRENT_TIMESTAMP + INTERVAL '1 hour')
            """,
        )
        insertBots()

        val picked = (1..20).map { botPersonaRepository.findRandomEnabled().awaitSingle().userId }.toSet()

        assertThat(picked).containsExactly(-1L)
    }

    @Test
    @DisplayName("모든 봇 id 는 매칭 가능 여부와 관계없이 읽는다")
    fun allBotUserIds() = runBlocking<Unit> {
        insertBots()

        assertThat(botQueueRepository.findAllBotUserIds()).containsExactly(-6L, -5L, -4L, -3L, -2L, -1L)
    }

    private suspend fun insertBots() {
        execute(
            """
            INSERT INTO users(id, mmr) VALUES (-1, 1100), (-2, 1200), (-3, 1300), (-4, 1400), (-5, 1500), (-6, 1600)
            """,
        )
        execute(
            """
            INSERT INTO bot_personas(user_id, name, enabled, hospitality, brain_type) VALUES
              (-1, 'rule', TRUE, FALSE, 'RULE'),
              (-2, 'jev', TRUE, FALSE, 'JEV'),
              (-3, 'openrouter', TRUE, FALSE, 'OPENROUTER'),
              (-4, 'disabled', FALSE, FALSE, 'RULE'),
              (-5, 'hospitality', TRUE, TRUE, 'RULE'),
              (-6, 'disabled llm', FALSE, FALSE, 'OPENROUTER')
            """,
        )
    }

    companion object {
        private const val SCHEMA = """
            DROP TABLE IF EXISTS bot_personas CASCADE;
            DROP TABLE IF EXISTS llm_api_keys CASCADE;
            DROP TABLE IF EXISTS users CASCADE;
            DROP TABLE IF EXISTS servers CASCADE;
            CREATE TABLE servers (
                id BIGINT PRIMARY KEY,
                type VARCHAR(10) NOT NULL,
                state VARCHAR(10) NOT NULL,
                target_bot_sessions INTEGER
            );
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                mmr SMALLINT DEFAULT 1000 NOT NULL
            );
            CREATE TABLE bot_personas (
                user_id BIGINT PRIMARY KEY,
                name VARCHAR(50) NOT NULL,
                enabled BOOLEAN DEFAULT TRUE NOT NULL,
                hospitality BOOLEAN DEFAULT FALSE NOT NULL,
                brain_type VARCHAR(20) DEFAULT 'RULE' NOT NULL,
                llm_model VARCHAR(100)
            );
            CREATE TABLE llm_api_keys (
                provider VARCHAR(20) PRIMARY KEY,
                encrypted_key TEXT NOT NULL,
                key_hint VARCHAR(16) NOT NULL,
                updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                unavailable_until TIMESTAMP WITHOUT TIME ZONE,
                unavailable_reason VARCHAR(200)
            )
        """
    }
}
