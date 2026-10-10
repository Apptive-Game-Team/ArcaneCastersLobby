package com.wordonline.matching.friend.repository

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.flow.toList
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
 * Runs [FriendRepository] against a real Postgres. A mocked repository cannot show that a scalar
 * `@Query` result is readable, which is how `areFriends` shipped with a 500 on friend search.
 *
 * Skipped unless `QUEST_IT_DATABASE_URL` is set, because the build has no database. Point it at an
 * EMPTY scratch database: the test drops and recreates `friendships` and a stub `users` table.
 *
 * ```
 * docker run -d --name lobby-friend-it-pg -e POSTGRES_USER=t -e POSTGRES_PASSWORD=t -e POSTGRES_DB=t \
 *   -p 55465:5432 postgres:16-alpine
 * QUEST_IT_DATABASE_URL=r2dbc:postgresql://t:t@localhost:55465/t \
 *   ./gradlew test --tests '*FriendRepositoryPostgresIntegrationTest'
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class FriendRepositoryPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var friendRepository: FriendRepository

    @BeforeAll
    fun connect() {
        connectionFactory = ConnectionFactories.get(System.getenv("QUEST_IT_DATABASE_URL"))
        databaseClient = DatabaseClient.create(connectionFactory)
        val repositoryFactory = R2dbcRepositoryFactory(R2dbcEntityTemplate(databaseClient, PostgresDialect.INSTANCE))
        friendRepository = repositoryFactory.getRepository(FriendRepository::class.java)
    }

    @AfterAll
    fun disconnect() {
        (connectionFactory as? io.r2dbc.spi.Closeable)?.let { closeable ->
            runBlocking { reactor.core.publisher.Mono.from(closeable.close()).awaitSingleOrNull() }
        }
    }

    @BeforeEach
    fun recreateSchema() = runBlocking<Unit> {
        SCHEMA.split(";").map(String::trim).filter(String::isNotEmpty).forEach { statement ->
            databaseClient.sql(statement).fetch().rowsUpdated().awaitSingle()
        }
    }

    @Test
    @DisplayName("existsByUserIdAndFriendId 는 친구 행이 있으면 true 없으면 false 를 읽는다")
    fun existsByUserIdAndFriendIdReadsBoolean() = runBlocking<Unit> {
        friendRepository.insertFriendship(1L, 2L)

        assertThat(friendRepository.existsByUserIdAndFriendId(1L, 2L)).isTrue()
        assertThat(friendRepository.existsByUserIdAndFriendId(2L, 1L)).isFalse()
        assertThat(friendRepository.existsByUserIdAndFriendId(1L, 3L)).isFalse()
    }

    @Test
    @DisplayName("findFriendIdsByUserId 는 최근에 맺은 친구부터 id 를 읽는다")
    fun findFriendIdsByUserIdReadsLongs() = runBlocking<Unit> {
        friendRepository.insertFriendship(1L, 2L)
        databaseClient.sql("INSERT INTO friendships(user_id, friend_id, created_at) VALUES (1, 3, CURRENT_TIMESTAMP + INTERVAL '1 minute')")
            .fetch().rowsUpdated().awaitSingle()

        assertThat(friendRepository.findFriendIdsByUserId(1L).toList()).containsExactly(3L, 2L)
    }

    @Test
    @DisplayName("deleteFriendship 은 양방향 행을 모두 지운다")
    fun deleteFriendshipRemovesBothDirections() = runBlocking<Unit> {
        friendRepository.insertFriendship(1L, 2L)
        friendRepository.insertFriendship(2L, 1L)

        assertThat(friendRepository.deleteFriendship(1L, 2L)).isEqualTo(2L)
        assertThat(friendRepository.existsByUserIdAndFriendId(1L, 2L)).isFalse()
    }

    companion object {
        private const val SCHEMA = """
            DROP TABLE IF EXISTS friendships;
            DROP TABLE IF EXISTS users;
            CREATE TABLE users (id BIGINT PRIMARY KEY);
            INSERT INTO users(id) VALUES (1), (2), (3);
            CREATE TABLE friendships (
                user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                friend_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
                PRIMARY KEY (user_id, friend_id),
                CONSTRAINT chk_friendship_distinct CHECK (user_id <> friend_id)
            )
        """
    }
}
