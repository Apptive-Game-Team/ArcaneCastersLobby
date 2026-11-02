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
 * Runs [BotPersonaRepository.findNearestMatchable] against a real Postgres, because the
 * matchable-persona rule compares `llm_api_keys.unavailable_until` with `CURRENT_TIMESTAMP` in SQL
 * and the MMR ordering lives in the query, neither of which a mock can show.
 *
 * Skipped unless `QUEST_IT_DATABASE_URL` is set. Point it at an EMPTY scratch database: the test
 * drops and recreates stub `users`, `bot_personas` and `llm_api_keys` tables carrying the columns
 * of WordOnlineDatabase V044 and V048.
 *
 * ```
 * docker run -d --name lobby-bot-persona-it-pg -e POSTGRES_USER=t -e POSTGRES_PASSWORD=t -e POSTGRES_DB=t \
 *   -p 55466:5432 postgres:16-alpine
 * QUEST_IT_DATABASE_URL=r2dbc:postgresql://t:t@localhost:55466/t \
 *   ./gradlew test --tests '*BotPersonaRepositoryPostgresIntegrationTest'
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class BotPersonaRepositoryPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var botPersonaRepository: BotPersonaRepository

    @BeforeAll
    fun connect() {
        connectionFactory = ConnectionFactories.get(System.getenv("QUEST_IT_DATABASE_URL"))
        databaseClient = DatabaseClient.create(connectionFactory)
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
    @DisplayName("RULE 봇과 key 를 쓸 수 있는 LLM 봇만 고르고 비활성 봇과 hospitality 봇은 뺀다")
    fun matchableRule() = runBlocking<Unit> {
        execute(
            """
            INSERT INTO llm_api_keys(provider, encrypted_key, key_hint, unavailable_until) VALUES
              ('JEV', 'v1:x', '1234', CURRENT_TIMESTAMP + INTERVAL '1 hour'),
              ('OPENROUTER', 'v1:y', '5678', CURRENT_TIMESTAMP - INTERVAL '1 minute')
            """,
        )
        execute("INSERT INTO users(id, mmr) VALUES (-1, 1000), (-2, 1000), (-3, 1000), (-4, 1000), (-5, 1000)")
        execute(
            """
            INSERT INTO bot_personas(user_id, name, enabled, hospitality, brain_type) VALUES
              (-1, 'rule', TRUE, FALSE, 'RULE'),
              (-2, 'jev unavailable', TRUE, FALSE, 'JEV'),
              (-3, 'openrouter available again', TRUE, FALSE, 'OPENROUTER'),
              (-4, 'disabled', FALSE, FALSE, 'RULE'),
              (-5, 'hospitality', TRUE, TRUE, 'RULE')
            """,
        )

        val picked = botPersonaRepository.findNearestMatchable(1000, 10).collectList().awaitSingle()

        assertThat(picked.map { it.userId }).containsExactlyInAnyOrder(-1L, -3L)
    }

    @Test
    @DisplayName("provider key 행이 없는 LLM 봇은 고르지 않고 unavailable_until 이 NULL 이면 고른다")
    fun missingKeyRowExcludes() = runBlocking<Unit> {
        execute("INSERT INTO llm_api_keys(provider, encrypted_key, key_hint) VALUES ('JEV', 'v1:x', '1234')")
        execute(
            """
            INSERT INTO bot_personas(user_id, name, enabled, hospitality, brain_type) VALUES
              (-1, 'jev', TRUE, FALSE, 'JEV'),
              (-2, 'openrouter without key', TRUE, FALSE, 'OPENROUTER')
            """,
        )

        val picked = botPersonaRepository.findNearestMatchable(1000, 10).collectList().awaitSingle()

        assertThat(picked.map { it.userId }).containsExactly(-1L)
    }

    @Test
    @DisplayName("MMR 이 가까운 순서로 limit 개만 고르고 users 행이 없는 봇은 1000 으로 본다")
    fun nearestMmr() = runBlocking<Unit> {
        execute("INSERT INTO users(id, mmr) VALUES (-1, 1500), (-2, 1180), (-3, 900), (-4, 1240)")
        execute(
            """
            INSERT INTO bot_personas(user_id, name, enabled, hospitality, brain_type) VALUES
              (-1, 'far', TRUE, FALSE, 'RULE'),
              (-2, 'near', TRUE, FALSE, 'RULE'),
              (-3, 'low', TRUE, FALSE, 'RULE'),
              (-4, 'nearer', TRUE, FALSE, 'RULE'),
              (-5, 'no users row', TRUE, FALSE, 'RULE')
            """,
        )

        val nearTwelveHundred = botPersonaRepository.findNearestMatchable(1200, 2).collectList().awaitSingle()
        val nearThousand = botPersonaRepository.findNearestMatchable(1000, 1).collectList().awaitSingle()

        assertThat(nearTwelveHundred.map { it.userId }).containsExactly(-2L, -4L)
        assertThat(nearThousand.map { it.userId }).containsExactly(-5L)
        val withoutModels = botPersonaRepository.findNearestMatchable(1200, 1, listOf(-2, -4))
            .collectList().awaitSingle()
        assertThat(withoutModels.map { it.userId }).containsExactly(-5L)
    }

    companion object {
        private const val SCHEMA = """
            DROP TABLE IF EXISTS bot_personas CASCADE;
            DROP TABLE IF EXISTS llm_api_keys CASCADE;
            DROP TABLE IF EXISTS users CASCADE;
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
