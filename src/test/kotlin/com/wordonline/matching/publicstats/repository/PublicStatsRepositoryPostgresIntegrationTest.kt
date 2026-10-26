package com.wordonline.matching.publicstats.repository

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.r2dbc.core.DatabaseClient

/** RANKING_IT_DATABASE_URL은 이 테스트 전용 빈 PostgreSQL 데이터베이스를 가리켜야 한다. */
@EnabledIfEnvironmentVariable(named = "RANKING_IT_DATABASE_URL", matches = ".+")
class PublicStatsRepositoryPostgresIntegrationTest {
    @Test
    fun `활성 봇만 사용자와 함께 경기 수와 MMR 기준으로 정렬한다`() = runBlocking<Unit> {
        val database = DatabaseClient.create(ConnectionFactories.get(System.getenv("RANKING_IT_DATABASE_URL")))
        val statements = listOf(
            "CREATE TABLE users (id BIGINT PRIMARY KEY, mmr INT NOT NULL)",
            "CREATE TABLE bot_personas (user_id BIGINT PRIMARY KEY, name TEXT NOT NULL, enabled BOOLEAN NOT NULL)",
            "CREATE TABLE statistic_games (id BIGINT PRIMARY KEY, outcome TEXT, win_user_id BIGINT)",
            "CREATE TABLE statistic_game_sessions (statistic_game_id BIGINT, left_user_id BIGINT, right_user_id BIGINT, game_type TEXT)",
            "INSERT INTO users VALUES (1, 1500), (-1, 1600), (-2, 2000), (-3, 1900), (-4, 1800)",
            "INSERT INTO bot_personas VALUES (-1, 'enabled', TRUE), (-2, 'disabled', FALSE), (-4, 'too few', TRUE)",
            "INSERT INTO statistic_games VALUES (1, 'WIN', -1), (2, 'DRAW', NULL), (3, 'WIN', -2), (4, 'WIN', -3), (5, 'WIN', -4)",
            "INSERT INTO statistic_game_sessions VALUES (1, -1, 1, 'PVP'), (2, 1, -1, 'PVP'), (3, -2, -3, 'PVP'), (4, -3, -2, 'PVP'), (5, -4, 1, 'PVP')",
        )
        try {
            for (sql in statements) database.sql(sql).fetch().rowsUpdated().awaitSingle()
            val candidates = PublicStatsRepository(database).findRankingCandidates(2, 10)
            assertThat(candidates.map { it.userId }).containsExactly(-1L, 1L)
            assertThat(candidates.first()).isEqualTo(RankingCandidate(-1, 1600, 1, 0, 1, "enabled"))
            assertThat(candidates.last().botName).isNull()
            assertThat(PublicStatsRepository(database).findRankingCandidates(2, 1).map { it.userId })
                .containsExactly(-1L)
        } finally {
            database.sql("DROP TABLE IF EXISTS statistic_game_sessions, statistic_games, bot_personas, users")
                .fetch().rowsUpdated().awaitSingle()
        }
    }
}
