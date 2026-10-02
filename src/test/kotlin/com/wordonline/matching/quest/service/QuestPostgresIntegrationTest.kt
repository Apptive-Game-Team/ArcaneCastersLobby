package com.wordonline.matching.quest.service

import com.wordonline.matching.deck.repository.UserCardRepository
import com.wordonline.matching.decoration.repository.UserDecorationRepository
import com.wordonline.matching.quest.condition.AdventureClearCondition
import com.wordonline.matching.quest.condition.QuestCondition
import com.wordonline.matching.quest.condition.StageClearCondition
import com.wordonline.matching.quest.condition.TotalWinCondition
import com.wordonline.matching.quest.domain.QuestState
import com.wordonline.matching.quest.dto.QuestProgressResponseDto
import com.wordonline.matching.quest.dto.QuestRewardDto
import com.wordonline.matching.quest.entity.Quest
import com.wordonline.matching.quest.repository.QuestConditionRepository
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import com.wordonline.matching.quest.repository.UserQuestRepository
import com.wordonline.matching.quest.reward.DecorationRewardGrantor
import com.wordonline.matching.quest.reward.MagicRewardGrantor
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the quest repositories, the claim transaction and the grantors against a real Postgres.
 * Mocks cannot show that the conditional `UPDATE` serializes concurrent claims or that a failed
 * grant rolls the claim back; this does.
 *
 * Skipped unless `QUEST_IT_DATABASE_URL` is set, because the build has no database. Point it at an
 * EMPTY scratch database: the test drops and recreates the tables it needs, shaped like the
 * WordOnlineDatabase migration that adds `condition_type` and `quest_rewards`.
 *
 * ```
 * docker run -d --name lobby-quest-it-pg -e POSTGRES_USER=quest -e POSTGRES_PASSWORD=quest \
 *   -e POSTGRES_DB=quest_it -p 55477:5432 postgres:16-alpine
 * QUEST_IT_DATABASE_URL=r2dbc:pool:postgresql://quest:quest@localhost:55477/quest_it \
 *   ./gradlew test --tests '*QuestPostgresIntegrationTest'
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class QuestPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var questRepository: QuestRepository
    private lateinit var questRewardRepository: QuestRewardRepository
    private lateinit var userQuestRepository: UserQuestRepository
    private lateinit var userCardRepository: UserCardRepository
    private lateinit var userDecorationRepository: UserDecorationRepository
    private lateinit var transactionalOperator: TransactionalOperator

    private val userId = 1L

    @BeforeAll
    fun connect() {
        connectionFactory = ConnectionFactories.get(System.getenv("QUEST_IT_DATABASE_URL"))
        databaseClient = DatabaseClient.create(connectionFactory)
        val repositoryFactory = R2dbcRepositoryFactory(R2dbcEntityTemplate(databaseClient, PostgresDialect.INSTANCE))
        questRepository = repositoryFactory.getRepository(QuestRepository::class.java)
        questRewardRepository = repositoryFactory.getRepository(QuestRewardRepository::class.java)
        userQuestRepository = repositoryFactory.getRepository(UserQuestRepository::class.java)
        userCardRepository = repositoryFactory.getRepository(UserCardRepository::class.java)
        userDecorationRepository = repositoryFactory.getRepository(UserDecorationRepository::class.java)
        transactionalOperator = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))
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

    private suspend fun exec(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun queryLong(sql: String): Long? =
        databaseClient.sql(sql).map { row, _ -> row.get(0, Number::class.java)?.toLong() }.one().awaitSingleOrNull()

    private suspend fun queryString(sql: String): String? =
        databaseClient.sql(sql).map { row, _ -> row.get(0, String::class.java) }.one().awaitSingleOrNull()

    private fun service(vararg extraConditions: QuestCondition): QuestService {
        val questConditionRepository = QuestConditionRepository(databaseClient)
        val registry = QuestRegistry(
            listOf(
                StageClearCondition(questConditionRepository),
                TotalWinCondition(questConditionRepository),
                AdventureClearCondition(questConditionRepository),
            ) +
                extraConditions,
            listOf(MagicRewardGrantor(userCardRepository), DecorationRewardGrantor(userDecorationRepository)),
        )
        return QuestService(questRepository, questRewardRepository, userQuestRepository, registry, transactionalOperator)
    }

    @Test
    @DisplayName("user_quests 행은_DEPRECATED_가_아닌_퀘스트에만_생기고_다시_불러도_늘지_않으며_새_퀘스트는_기존_사용자에게도_생긴다")
    fun insertMissingSkipsDeprecatedAndIsIdempotent() = runBlocking<Unit> {
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (1, 5, 'DEFAULT', 'TOTAL_WIN'), (2, 5, 'DEPRECATED', 'TOTAL_WIN')")

        assertThat(userQuestRepository.insertMissing(userId).awaitSingle()).isEqualTo(1L)
        assertThat(userQuestRepository.insertMissing(userId).awaitSingle()).isEqualTo(0L)
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (3, 5, 'DEFAULT', 'TOTAL_WIN')")
        assertThat(userQuestRepository.insertMissing(userId).awaitSingle()).isEqualTo(1L)
        // A user id with no users row gets nothing rather than a foreign key error.
        assertThat(userQuestRepository.insertMissing(999L).awaitSingle()).isEqualTo(0L)

        assertThat(queryString("SELECT string_agg(quest_id::text || ':' || state, ',' ORDER BY quest_id) FROM user_quests WHERE user_id = 1"))
            .isEqualTo("1:IN_PROGRESS,3:IN_PROGRESS")
    }

    @Test
    @DisplayName("check 는_DEPRECATED_퀘스트를_claim_하지_않는다")
    fun deprecatedQuestIsNeverClaimed() = runBlocking<Unit> {
        exec("UPDATE users SET total_wins = 10 WHERE id = 1")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (2, 1, 'DEPRECATED', 'TOTAL_WIN')")
        exec("INSERT INTO quest_rewards(quest_id, reward_type, target_id, amount) VALUES (2, 'MAGIC', 100, 1)")
        // A row left over from before DEPRECATED was respected.
        exec("INSERT INTO user_quests(user_id, quest_id, state) VALUES (1, 2, 'IN_PROGRESS')")

        assertThat(service().checkQuestsWithRewards(userId)).isEmpty()
        assertThat(queryString("SELECT state FROM user_quests WHERE user_id = 1 AND quest_id = 2")).isEqualTo("IN_PROGRESS")
    }

    @Test
    @DisplayName("동시에_들어온_check_여러_개가_모두_IN_PROGRESS_를_읽어도_보상은_한_번만_준다")
    fun concurrentChecksGrantOnce() = runBlocking<Unit> {
        val callers = 6
        val arrived = AtomicInteger()
        val allArrived = CompletableDeferred<Unit>()
        // Holds every caller after it has read the quest as claimable, so they all reach the
        // claim UPDATE together and only the database decides who wins.
        val barrier = object : QuestCondition {
            override val type = "BARRIER"
            override suspend fun progress(userId: Long, quest: Quest): Int {
                if (arrived.incrementAndGet() == callers) allArrived.complete(Unit)
                withTimeout(10_000) { allArrived.await() }
                return 1
            }
        }
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (1, 1, 'DEFAULT', 'BARRIER')")
        exec("INSERT INTO quest_rewards(quest_id, reward_type, target_id, amount) VALUES (1, 'MAGIC', 100, 2), (1, 'DECORATION', 200, 1)")
        exec("INSERT INTO user_magics(user_id, magic_id, count) VALUES (1, 100, 3)")
        val questService = service(barrier)

        val results = (1..callers).map { async(Dispatchers.IO) { questService.checkQuestsWithRewards(userId) } }.awaitAll()

        assertThat(results.flatten()).containsExactly(
            QuestRewardDto("MAGIC", 100L, 2, 1L),
            QuestRewardDto("DECORATION", 200L, 1, 1L),
        )
        assertThat(arrived.get()).isEqualTo(callers)
        assertThat(queryLong("SELECT count FROM user_magics WHERE user_id = 1 AND magic_id = 100")).isEqualTo(5L)
        assertThat(queryLong("SELECT COUNT(*) FROM user_decorations WHERE user_id = 1 AND decoration_id = 200")).isEqualTo(1L)
        assertThat(queryString("SELECT state FROM user_quests WHERE user_id = 1 AND quest_id = 1")).isEqualTo("COMPLETED")
    }

    @Test
    @DisplayName("보상_하나가_실패하면_claim_과_먼저_준_보상이_함께_rollback_되고_다음_check_에서_다시_시도한다")
    fun grantorFailureRollsBackClaimAndEarlierGrants() = runBlocking<Unit> {
        exec("UPDATE users SET total_wins = 1 WHERE id = 1")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (1, 1, 'DEFAULT', 'TOTAL_WIN'), (2, 1, 'DEFAULT', 'TOTAL_WIN')")
        // Quest 1: the first reward is fine, the second has no target_id.
        exec("INSERT INTO quest_rewards(id, quest_id, reward_type, target_id, amount) VALUES (10, 1, 'MAGIC', 100, 2), (11, 1, 'MAGIC', NULL, 1), (20, 2, 'DECORATION', 200, 1)")

        val rewards = service().checkQuestsWithRewards(userId)

        assertThat(rewards).containsExactly(QuestRewardDto("DECORATION", 200L, 1, 2L))
        assertThat(queryString("SELECT state FROM user_quests WHERE user_id = 1 AND quest_id = 1")).isEqualTo("IN_PROGRESS")
        assertThat(queryString("SELECT state FROM user_quests WHERE user_id = 1 AND quest_id = 2")).isEqualTo("COMPLETED")
        assertThat(queryLong("SELECT COUNT(*) FROM user_magics WHERE user_id = 1")).isEqualTo(0L)

        // Fix the data; the next check grants quest 1 exactly once.
        exec("UPDATE quest_rewards SET target_id = 101 WHERE id = 11")
        assertThat(service().checkQuestsWithRewards(userId)).containsExactly(
            QuestRewardDto("MAGIC", 100L, 2, 1L),
            QuestRewardDto("MAGIC", 101L, 1, 1L),
        )
        assertThat(service().checkQuestsWithRewards(userId)).isEmpty()
        assertThat(queryLong("SELECT count FROM user_magics WHERE user_id = 1 AND magic_id = 100")).isEqualTo(2L)
    }

    @Test
    @DisplayName("퀘스트_목록은_DEPRECATED_를_빼고_quest_id_순이며_아무것도_쓰지_않는다")
    fun questListExcludesDeprecatedAndIsReadOnly() = runBlocking<Unit> {
        exec("UPDATE users SET total_wins = 4 WHERE id = 1")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type, condition_target_id) VALUES (3, 1, 'DEFAULT', 'STAGE_CLEAR', 50), (1, 10, 'DEFAULT', 'TOTAL_WIN', NULL), (2, 1, 'DEPRECATED', 'TOTAL_WIN', NULL)")
        exec("INSERT INTO quest_rewards(id, quest_id, reward_type, target_id, amount) VALUES (5, 3, 'DECORATION', 200, 1), (4, 3, 'MAGIC', 100, 2), (6, 2, 'MAGIC', 101, 1), (7, 1, 'MAGIC', 102, 3)")
        exec("INSERT INTO stages(id) VALUES (50)")
        exec("INSERT INTO scenarios(id, stage_id) VALUES (500, 50), (501, 50)")
        exec("INSERT INTO user_scenarios(user_id, scenario_id, state) VALUES (1, 500, 'FINISHED'), (1, 501, 'FINISHED')")

        val quests = service().findMyQuests(userId)

        assertThat(quests.map { it.questId }).containsExactly(1L, 3L)
        assertThat(quests[0].state).isEqualTo(QuestState.IN_PROGRESS)
        assertThat(quests[0].progress).isEqualTo(4)
        assertThat(quests[1].conditionTargetId).isEqualTo(50L)
        assertThat(quests[1].progress).isEqualTo(1)
        assertThat(quests[1].rewards.map { it.rewardId }).containsExactly(100L, 200L)
        assertThat(queryLong("SELECT COUNT(*) FROM user_quests")).isEqualTo(0L)
    }

    @Test
    @DisplayName("같은_보상을_주는_퀘스트가_둘이면_DEPRECATED_가_아닌_것_중_id_가_작은_것을_읽는다")
    fun progressByRewardPicksOneQuestDeterministically() = runBlocking<Unit> {
        exec("UPDATE users SET total_wins = 2 WHERE id = 1")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (1, 9, 'DEPRECATED', 'TOTAL_WIN'), (4, 7, 'DEFAULT', 'TOTAL_WIN'), (3, 5, 'DEFAULT', 'TOTAL_WIN')")
        exec("INSERT INTO quest_rewards(quest_id, reward_type, target_id, amount) VALUES (1, 'MAGIC', 100, 1), (4, 'MAGIC', 100, 1), (3, 'MAGIC', 100, 1)")

        assertThat(service().findQuestProgressByCard(userId, 100L))
            .isEqualTo(QuestProgressResponseDto(QuestState.IN_PROGRESS, 2, 5))
        assertThat(service().findQuestProgressByDecoration(userId, 100L)).isNull()
        assertThat(queryLong("SELECT COUNT(*) FROM user_quests")).isEqualTo(0L)
    }

    @Test
    @DisplayName("ADVENTURE_CLEAR 는_그_모험의_스테이지를_모두_끝내야_보상을_주고_다른_모험의_스테이지는_세지_않는다")
    fun adventureClearCountsOnlyThatAdventure() = runBlocking<Unit> {
        exec("INSERT INTO stages(id, adventure_id) VALUES (50, 7), (51, 7), (60, 8)")
        exec("INSERT INTO scenarios(id, stage_id) VALUES (500, 50), (510, 51), (600, 60)")
        exec("INSERT INTO user_scenarios(user_id, scenario_id, state) VALUES (1, 500, 'FINISHED'), (1, 510, 'ACTIVE'), (1, 600, 'FINISHED')")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type, condition_target_id) VALUES (1, 2, 'DEFAULT', 'ADVENTURE_CLEAR', 7)")
        exec("INSERT INTO quest_rewards(quest_id, reward_type, target_id, amount) VALUES (1, 'MAGIC', 100, 1)")

        assertThat(service().checkQuestsWithRewards(userId)).isEmpty()
        assertThat(service().findMyQuests(userId).single().progress).isEqualTo(1)

        exec("UPDATE user_scenarios SET state = 'FINISHED' WHERE scenario_id = 510")
        assertThat(service().checkQuestsWithRewards(userId)).containsExactly(QuestRewardDto("MAGIC", 100L, 1, 1L))
        assertThat(service().findMyQuests(userId).single().state).isEqualTo(QuestState.COMPLETED)
    }

    @Test
    @DisplayName("condition_target_id 가_없는_ADVENTURE_CLEAR 퀘스트는_check_에서_건너뛰고_다른_퀘스트는_지급한다")
    fun adventureClearWithoutTargetIsSkipped() = runBlocking<Unit> {
        exec("UPDATE users SET total_wins = 1 WHERE id = 1")
        exec("INSERT INTO quests(id, require_value, access_type, condition_type) VALUES (1, 0, 'DEFAULT', 'ADVENTURE_CLEAR'), (2, 1, 'DEFAULT', 'TOTAL_WIN')")
        exec("INSERT INTO quest_rewards(quest_id, reward_type, target_id, amount) VALUES (1, 'MAGIC', 100, 1), (2, 'MAGIC', 101, 1)")

        assertThat(service().checkQuestsWithRewards(userId)).containsExactly(QuestRewardDto("MAGIC", 101L, 1, 2L))
        assertThat(queryString("SELECT state FROM user_quests WHERE user_id = 1 AND quest_id = 1")).isEqualTo("IN_PROGRESS")
    }

    companion object {
        /**
         * The columns these queries touch, shaped like the live database after the migration that
         * adds `condition_type` and `quest_rewards`: the legacy quest columns are nullable,
         * `user_quests` is unique on (`user_id`, `quest_id`), and `user_magics.count` defaults to 3.
         */
        private val SCHEMA = """
            DROP TABLE IF EXISTS user_scenarios, scenarios, stages, user_quests, quest_rewards, reward_params, quests,
                user_decorations, decorations, user_magics, magics, users CASCADE;
            CREATE TABLE users (id bigint PRIMARY KEY, total_wins integer DEFAULT 0);
            CREATE TABLE magics (id bigint PRIMARY KEY);
            CREATE TABLE user_magics (
                id bigserial PRIMARY KEY,
                user_id bigint REFERENCES users ON DELETE CASCADE,
                magic_id bigint REFERENCES magics ON DELETE CASCADE,
                count integer NOT NULL DEFAULT 3,
                CONSTRAINT uq_user_magics_user_id_magic_id UNIQUE (user_id, magic_id)
            );
            CREATE TABLE decorations (id bigint PRIMARY KEY);
            CREATE TABLE user_decorations (
                id bigserial PRIMARY KEY,
                user_id bigint,
                decoration_id bigint REFERENCES decorations,
                is_equipped boolean DEFAULT false
            );
            CREATE TABLE quests (
                id bigserial PRIMARY KEY,
                progress_checker varchar(31),
                require_value integer NOT NULL,
                reward_giver varchar(31),
                access_type varchar(10) NOT NULL DEFAULT 'DEFAULT',
                condition_type varchar(31) NOT NULL,
                condition_target_id bigint
            );
            CREATE TABLE quest_rewards (
                id bigserial PRIMARY KEY,
                quest_id bigint NOT NULL REFERENCES quests (id) ON DELETE CASCADE,
                reward_type varchar(31) NOT NULL,
                target_id bigint,
                amount integer NOT NULL DEFAULT 1 CHECK (amount > 0)
            );
            CREATE TABLE user_quests (
                id bigserial PRIMARY KEY,
                user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE,
                quest_id bigint NOT NULL REFERENCES quests ON DELETE CASCADE,
                state varchar(15) NOT NULL DEFAULT 'IN_PROGRESS',
                CONSTRAINT uq_user_quests_user_quest UNIQUE (user_id, quest_id)
            );
            CREATE TABLE stages (id bigint PRIMARY KEY, adventure_id bigint);
            CREATE TABLE scenarios (id bigint PRIMARY KEY, stage_id bigint REFERENCES stages);
            CREATE TABLE user_scenarios (
                id bigserial PRIMARY KEY,
                user_id bigint,
                scenario_id bigint REFERENCES scenarios,
                state varchar(15),
                UNIQUE (user_id, scenario_id)
            );
            INSERT INTO users (id) VALUES (1);
            INSERT INTO magics (id) VALUES (100), (101), (102);
            INSERT INTO decorations (id) VALUES (200)
        """.trimIndent()
    }
}
