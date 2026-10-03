package com.wordonline.matching.chest.service

import com.wordonline.matching.appearance.repository.AppearanceRepository
import com.wordonline.matching.appearance.service.AppearanceSelection
import com.wordonline.matching.appearance.service.AppearanceService
import com.wordonline.matching.chest.dto.ChestRewardDto
import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.deck.repository.UserCardRepository
import com.wordonline.matching.decoration.repository.UserDecorationRepository
import com.wordonline.matching.quest.condition.AdventureClearCondition
import com.wordonline.matching.quest.condition.StageClearCondition
import com.wordonline.matching.quest.condition.TotalWinCondition
import com.wordonline.matching.quest.repository.QuestConditionRepository
import com.wordonline.matching.quest.repository.QuestRepository
import com.wordonline.matching.quest.repository.QuestRewardRepository
import com.wordonline.matching.quest.repository.UserQuestRepository
import com.wordonline.matching.quest.reward.AppearanceRewardGrantor
import com.wordonline.matching.quest.reward.ChestRewardGrantor
import com.wordonline.matching.quest.reward.DecorationRewardGrantor
import com.wordonline.matching.quest.reward.MagicRewardGrantor
import com.wordonline.matching.quest.reward.Reward
import com.wordonline.matching.quest.reward.RewardGrantor
import com.wordonline.matching.quest.reward.RewardNotGrantableException
import com.wordonline.matching.quest.service.QuestRegistry
import com.wordonline.matching.quest.service.QuestRegistryStartupCheck
import com.wordonline.matching.quest.service.UnknownQuestTypeException
import com.wordonline.matching.support.MigratedPostgresDatabase
import io.r2dbc.spi.Closeable
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

/**
 * Runs the appearance and chest code against a real Postgres built from the WordOnlineDatabase
 * migrations, including the V020..V022 catalog and the chest contents V024 adds.
 *
 * Gated on the same `QUEST_IT_DATABASE_URL` as [com.wordonline.matching.quest.service.QuestPostgresIntegrationTest].
 * [MigratedPostgresDatabase] builds the database [DATABASE_NAME] from `QUEST_IT_MIGRATION_DIR`,
 * which must hold V024 or later. The claim of the adventure quests that hand out these chests is
 * covered by [com.wordonline.matching.quest.service.QuestClaimPostgresIntegrationTest].
 *
 * ```
 * QUEST_IT_DATABASE_URL=r2dbc:pool:postgresql://quest:quest@localhost:55477/quest_it \
 * QUEST_IT_MIGRATION_DIR=/path/to/database/migration \
 *   ./gradlew test --tests '*AppearanceChestPostgresIntegrationTest'
 * ```
 *
 * Each test works on a fresh `users` row, so the tests share the migrated catalog without
 * interfering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class AppearanceChestPostgresIntegrationTest {

    private lateinit var connectionFactory: ConnectionFactory
    private lateinit var databaseClient: DatabaseClient
    private lateinit var questRepository: QuestRepository
    private lateinit var questRewardRepository: QuestRewardRepository
    private lateinit var userQuestRepository: UserQuestRepository
    private lateinit var userCardRepository: UserCardRepository
    private lateinit var userDecorationRepository: UserDecorationRepository
    private lateinit var appearanceRepository: AppearanceRepository
    private lateinit var chestRepository: ChestRepository
    private lateinit var transactionalOperator: TransactionalOperator

    private var userId = 0L

    @BeforeAll
    fun buildDatabaseFromMigrations() = runBlocking<Unit> {
        connectionFactory = MigratedPostgresDatabase.build(DATABASE_NAME, requiredVersion = 24)
        databaseClient = DatabaseClient.create(connectionFactory)
        val repositoryFactory = R2dbcRepositoryFactory(R2dbcEntityTemplate(databaseClient, PostgresDialect.INSTANCE))
        questRepository = repositoryFactory.getRepository(QuestRepository::class.java)
        questRewardRepository = repositoryFactory.getRepository(QuestRewardRepository::class.java)
        userQuestRepository = repositoryFactory.getRepository(UserQuestRepository::class.java)
        userCardRepository = repositoryFactory.getRepository(UserCardRepository::class.java)
        userDecorationRepository = repositoryFactory.getRepository(UserDecorationRepository::class.java)
        appearanceRepository = AppearanceRepository(databaseClient)
        chestRepository = ChestRepository(databaseClient)
        transactionalOperator = TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))
    }

    @AfterAll
    fun disconnect() {
        (connectionFactory as? Closeable)?.let { closeable ->
            runBlocking { Mono.from(closeable.close()).awaitSingleOrNull() }
        }
    }

    @BeforeEach
    fun createUser() = runBlocking<Unit> {
        userId = queryLong("INSERT INTO users (id) SELECT COALESCE(MAX(id), 0) + 1 FROM users RETURNING id")!!
    }

    private suspend fun exec(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun queryLong(sql: String): Long? =
        databaseClient.sql(sql).map { row, _ -> row.get(0, Number::class.java)?.toLong() }.one().awaitSingleOrNull()

    private suspend fun queryString(sql: String): String? =
        databaseClient.sql(sql).map { row, _ -> row.get(0, String::class.java) }.one().awaitSingleOrNull()

    private suspend fun appearanceId(key: String): Long = queryLong("SELECT id FROM appearances WHERE key = '$key'")!!

    private suspend fun chestId(key: String): Long = queryLong("SELECT id FROM chests WHERE key = '$key'")!!

    private suspend fun ownedAppearanceCount(key: String): Long =
        queryLong(
            "SELECT COUNT(*) FROM user_appearances ua JOIN appearances a ON a.id = ua.appearance_id " +
                "WHERE ua.user_id = $userId AND a.key = '$key'",
        )!!

    private fun grantors(appearanceGrantor: RewardGrantor = AppearanceRewardGrantor(appearanceRepository)): List<RewardGrantor> =
        listOf(
            MagicRewardGrantor(userCardRepository),
            DecorationRewardGrantor(userDecorationRepository),
            appearanceGrantor,
            ChestRewardGrantor(chestRepository),
        )

    private fun registry(grantors: List<RewardGrantor> = grantors()): QuestRegistry {
        val questConditionRepository = QuestConditionRepository(databaseClient)
        return QuestRegistry(
            listOf(
                StageClearCondition(questConditionRepository),
                TotalWinCondition(questConditionRepository),
                AdventureClearCondition(questConditionRepository),
            ),
            grantors,
        )
    }

    private fun chestService(grantors: List<RewardGrantor> = grantors()) =
        ChestService(chestRepository, registry(grantors), transactionalOperator)

    /** Gives the user one unopened chest through the real grantor and returns its `user_chests.id`. */
    private suspend fun giveChest(chestKey: String): Long {
        ChestRewardGrantor(chestRepository).grant(userId, testReward("CHEST", chestId(chestKey)))
        return queryLong("SELECT MAX(id) FROM user_chests WHERE user_id = $userId")!!
    }

    private fun testReward(type: String, targetId: Long?, amount: Int = 1) = object : Reward {
        override val rewardType = type
        override val targetId = targetId
        override val amount = amount
        override fun describeRow() = "test reward"
    }

    @Test
    @DisplayName("같은_상자를_동시에_여러_번_열어도_한_번만_열리고_한_번만_지급한다")
    fun concurrentOpensGrantOnce() = runBlocking<Unit> {
        val userChestId = giveChest("forest_chest")
        // Holds the row lock for a while after claiming, so the other callers block on the UPDATE
        // and then have to find the chest opened.
        val realGrantor = AppearanceRewardGrantor(appearanceRepository)
        val slowGrantor = object : RewardGrantor by realGrantor {
            override suspend fun grant(userId: Long, reward: Reward) {
                delay(300)
                realGrantor.grant(userId, reward)
            }
        }
        val service = chestService(grantors(slowGrantor))

        val results = (1..6).map { async(Dispatchers.IO) { service.openChest(userId, userChestId) } }.awaitAll()

        assertThat(results.filterIsInstance<ChestOpenResult.Opened>().single().rewards.map { it.rewardType to it.rewardKey })
            .containsExactlyInAnyOrder("APPEARANCE" to "grass", "MAGIC" to null, "MAGIC" to null, "MAGIC" to null, "MAGIC" to null)
        assertThat(results.filter { it == ChestOpenResult.AlreadyOpened }).hasSize(5)
        assertThat(ownedAppearanceCount("grass")).isEqualTo(1L)
        assertThat(queryLong("SELECT COUNT(*) FROM user_chests WHERE id = $userChestId AND opened_at IS NOT NULL")).isEqualTo(1L)
    }

    @Test
    @DisplayName("내용물_하나의_지급이_실패하면_상자는_열리지_않은_채로_남고_먼저_준_외형도_rollback_된다")
    fun failingGrantorRollsBackOpen() = runBlocking<Unit> {
        val chestKey = "it_failing_$userId"
        val failingChestId = queryLong("INSERT INTO chests (key) VALUES ('$chestKey') RETURNING id")!!
        exec(
            "INSERT INTO chest_rewards (chest_id, reward_type, target_id, amount) VALUES " +
                "($failingChestId, 'APPEARANCE', ${appearanceId("storm")}, 1), ($failingChestId, 'DECORATION', NULL, 1)",
        )
        val userChestId = giveChest(chestKey)

        assertThrows<RewardNotGrantableException> { chestService().openChest(userId, userChestId) }

        assertThat(queryLong("SELECT COUNT(*) FROM user_chests WHERE id = $userChestId AND opened_at IS NULL")).isEqualTo(1L)
        assertThat(ownedAppearanceCount("storm")).isZero()

        // Fix the data; the same chest now opens once.
        exec("DELETE FROM chest_rewards WHERE chest_id = $failingChestId AND reward_type = 'DECORATION'")
        assertThat(chestService().openChest(userId, userChestId))
            .isEqualTo(ChestOpenResult.Opened(listOf(ChestRewardDto("APPEARANCE", appearanceId("storm"), "storm", 1))))
        assertThat(chestService().openChest(userId, userChestId)).isEqualTo(ChestOpenResult.AlreadyOpened)
        assertThat(ownedAppearanceCount("storm")).isEqualTo(1L)
    }

    @Test
    @DisplayName("다른_사용자의_상자와_없는_상자는_NotFound_다")
    fun otherUsersChestIsNotFound() = runBlocking<Unit> {
        val userChestId = giveChest("forest_chest")
        val otherUserId = queryLong("INSERT INTO users (id) SELECT MAX(id) + 1 FROM users RETURNING id")!!

        assertThat(chestService().openChest(otherUserId, userChestId)).isEqualTo(ChestOpenResult.NotFound)
        assertThat(chestService().openChest(userId, Long.MAX_VALUE)).isEqualTo(ChestOpenResult.NotFound)
        assertThat(queryLong("SELECT COUNT(*) FROM user_chests WHERE id = $userChestId AND opened_at IS NULL")).isEqualTo(1L)
    }

    @Test
    @DisplayName("가지지_않은_외형을_고르면_NotOwned_이고_users_appearance_는_그대로다")
    fun selectingUnownedAppearanceIsRejected() = runBlocking<Unit> {
        val service = AppearanceService(appearanceRepository)

        assertThat(service.selectAppearance(userId, "storm")).isEqualTo(AppearanceSelection.NotOwned)
        assertThat(service.selectAppearance(userId, "no_such_key")).isEqualTo(AppearanceSelection.UnknownKey)
        assertThat(queryString("SELECT appearance FROM users WHERE id = $userId")).isEqualTo("default")

        AppearanceRewardGrantor(appearanceRepository).grant(userId, testReward("APPEARANCE", appearanceId("storm")))
        assertThat(service.selectAppearance(userId, "storm")).isEqualTo(AppearanceSelection.Selected("storm"))
        assertThat(queryString("SELECT appearance FROM users WHERE id = $userId")).isEqualTo("storm")
        assertThat(service.selectAppearance(userId, "default")).isEqualTo(AppearanceSelection.Selected("default"))

        val catalog = service.findMyAppearances(userId)
        assertThat(catalog.map { it.key }).startsWith("default", "storm")
        assertThat(catalog.filter { it.owned }.map { it.key }).containsExactly("default", "storm")
        assertThat(catalog.filter { it.selected }.map { it.key }).containsExactly("default")
    }

    @Test
    @DisplayName("이미_가진_외형을_두_번_주어도_행은_하나이고_default_는_행을_만들지_않는다")
    fun grantingOwnedAppearanceTwiceKeepsOneRow() = runBlocking<Unit> {
        val grantor = AppearanceRewardGrantor(appearanceRepository)

        repeat(2) { grantor.grant(userId, testReward("APPEARANCE", appearanceId("blaze"))) }
        grantor.grant(userId, testReward("APPEARANCE", appearanceId("default")))

        assertThat(ownedAppearanceCount("blaze")).isEqualTo(1L)
        assertThat(queryLong("SELECT COUNT(*) FROM user_appearances WHERE user_id = $userId")).isEqualTo(1L)
    }

    @Test
    @DisplayName("시작_검사는_실제_migration_데이터를_통과하고_chest_rewards_에_CHEST_가_들어가면_실패한다")
    fun startupCheckAgainstRealData() = runBlocking<Unit> {
        val check = QuestRegistryStartupCheck(questRepository, questRewardRepository, chestRepository, registry())
        check.verify()

        val rowId = queryLong(
            "INSERT INTO chest_rewards (chest_id, reward_type, target_id, amount) " +
                "VALUES (${chestId("forest_chest")}, 'CHEST', ${chestId("fortress_chest")}, 1) RETURNING id",
        )!!
        try {
            assertThatThrownBy { runBlocking { check.verify() } }
                .isInstanceOf(UnknownQuestTypeException::class.java)
                .hasMessageContaining("chest_rewards $rowId")
                .hasMessageContaining("a chest cannot contain a chest")
        } finally {
            exec("DELETE FROM chest_rewards WHERE id = $rowId")
        }
    }

    companion object {
        private const val DATABASE_NAME = "appearance_chest_it"
    }
}
