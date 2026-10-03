package com.wordonline.matching.quest.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wordonline.matching.appearance.repository.AppearanceRepository
import com.wordonline.matching.appearance.service.AppearanceSelection
import com.wordonline.matching.appearance.service.AppearanceService
import com.wordonline.matching.chest.dto.ChestRewardDto
import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.chest.service.ChestOpenResult
import com.wordonline.matching.chest.service.ChestService
import com.wordonline.matching.deck.repository.UserCardRepository
import com.wordonline.matching.decoration.repository.UserDecorationRepository
import com.wordonline.matching.quest.condition.AdventureClearCondition
import com.wordonline.matching.quest.condition.StageClearCondition
import com.wordonline.matching.quest.condition.TotalWinCondition
import com.wordonline.matching.quest.controller.QuestClaimController
import com.wordonline.matching.quest.domain.QuestState
import com.wordonline.matching.quest.dto.QuestClaimResponseDto
import com.wordonline.matching.quest.dto.QuestSummaryRewardDto
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
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono

/**
 * Runs `claim_mode` and the explicit quest claim against a real Postgres built from the
 * WordOnlineDatabase migrations through V024, which adds `quests.claim_mode`, sets the two
 * `ADVENTURE_CLEAR` quests to `MANUAL` and deprecates quests 5 to 10. Each chest still holds the
 * single `APPEARANCE` reward V021 gave it.
 *
 * Gated on `QUEST_IT_DATABASE_URL`, like the other Postgres tests. [MigratedPostgresDatabase] builds
 * the database [DATABASE_NAME] from `QUEST_IT_MIGRATION_DIR`.
 *
 * ```
 * QUEST_IT_DATABASE_URL=r2dbc:pool:postgresql://quest:quest@localhost:55477/quest_it \
 * QUEST_IT_MIGRATION_DIR=/path/to/database/migration \
 *   ./gradlew test --tests '*QuestClaimPostgresIntegrationTest'
 * ```
 *
 * The HTTP status is checked by calling [QuestClaimController] directly with the real service, so
 * the mapping from the claim result to 200, 404, 409 and 422 runs against the database too. Each
 * test works on a fresh `users` row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = MigratedPostgresDatabase.DATABASE_URL_VARIABLE, matches = ".+")
class QuestClaimPostgresIntegrationTest {

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

    private suspend fun adventureId(name: String): Long = queryLong("SELECT id FROM adventures WHERE name = '$name'")!!

    private suspend fun adventureQuestId(adventureName: String): Long =
        queryLong(
            "SELECT id FROM quests WHERE condition_type = 'ADVENTURE_CLEAR' AND condition_target_id = ${adventureId(adventureName)}",
        )!!

    private suspend fun chestId(key: String): Long = queryLong("SELECT id FROM chests WHERE key = '$key'")!!

    private suspend fun appearanceId(key: String): Long = queryLong("SELECT id FROM appearances WHERE key = '$key'")!!

    private suspend fun userQuestState(questId: Long): String? =
        queryString("SELECT state FROM user_quests WHERE user_id = $userId AND quest_id = $questId")

    private suspend fun userChestCount(): Long = queryLong("SELECT COUNT(*) FROM user_chests WHERE user_id = $userId")!!

    /**
     * Gives the user a `user_scenarios` row for every scenario of the adventure, all `FINISHED`, or
     * with [leaveLastActive] the adventure's last scenario `ACTIVE`. A stage counts as finished only
     * when every row of it is `FINISHED`, so that leaves the adventure one stage short.
     */
    private suspend fun finishAdventure(name: String, leaveLastActive: Boolean = false) {
        exec(
            "INSERT INTO user_scenarios (user_id, scenario_id, state) " +
                "SELECT $userId, sc.id, 'FINISHED' FROM scenarios sc JOIN stages st ON st.id = sc.stage_id " +
                "WHERE st.adventure_id = ${adventureId(name)}",
        )
        if (leaveLastActive) {
            exec(
                "UPDATE user_scenarios SET state = 'ACTIVE' WHERE user_id = $userId AND scenario_id = " +
                    "(SELECT MAX(sc.id) FROM scenarios sc JOIN stages st ON st.id = sc.stage_id WHERE st.adventure_id = ${adventureId(name)})",
            )
        }
    }

    private fun grantors(chestGrantor: RewardGrantor = ChestRewardGrantor(chestRepository)): List<RewardGrantor> =
        listOf(
            MagicRewardGrantor(userCardRepository),
            DecorationRewardGrantor(userDecorationRepository),
            AppearanceRewardGrantor(appearanceRepository),
            chestGrantor,
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

    private fun questService(grantors: List<RewardGrantor> = grantors()) =
        QuestService(questRepository, questRewardRepository, userQuestRepository, registry(grantors), transactionalOperator)

    private fun controller(service: QuestService = questService()) = QuestClaimController(service)

    @Test
    @DisplayName("V024_는_ADVENTURE_CLEAR_퀘스트_11_12_를_MANUAL_로_하고_나머지는_AUTO_로_둔다")
    fun migrationSetsClaimModes() = runBlocking<Unit> {
        assertThat(adventureQuestId("forest")).isEqualTo(11L)
        assertThat(adventureQuestId("fortress")).isEqualTo(12L)
        assertThat(queryString("SELECT string_agg(id::text || ':' || claim_mode, ',' ORDER BY id) FROM quests WHERE claim_mode <> 'AUTO'"))
            .isEqualTo("11:MANUAL,12:MANUAL")
    }

    @Test
    @DisplayName("자동_check_는_조건을_채운_MANUAL_퀘스트를_지급하지_않고_행만_IN_PROGRESS_로_만든다")
    fun automaticCheckSkipsManualQuest() = runBlocking<Unit> {
        val questId = adventureQuestId("forest")
        finishAdventure("forest")
        val service = questService()

        assertThat(service.checkQuestsWithRewards(userId).filter { it.questId == questId }).isEmpty()
        assertThat(service.checkQuestsWithRewards(userId).filter { it.questId == questId }).isEmpty()

        assertThat(userQuestState(questId)).isEqualTo("IN_PROGRESS")
        assertThat(userChestCount()).isZero()
        assertThat(service.findMyQuests(userId).single { it.questId == questId }.claimable).isTrue()
    }

    @Test
    @DisplayName("동시에_들어온_claim_6개_중_하나만_200_으로_상자를_주고_나머지는_409_다")
    fun concurrentClaimsGrantOnce() = runBlocking<Unit> {
        val questId = adventureQuestId("forest")
        finishAdventure("forest")
        // Holds the row lock for a while after claiming, so the other callers block on the UPDATE
        // and then have to find the quest COMPLETED.
        val realGrantor = ChestRewardGrantor(chestRepository)
        val slowGrantor = object : RewardGrantor by realGrantor {
            override suspend fun grant(userId: Long, reward: Reward) {
                delay(300)
                realGrantor.grant(userId, reward)
            }
        }
        val controller = controller(questService(grantors(slowGrantor)))

        val responses = (1..6).map { async(Dispatchers.IO) { controller.claimQuest(userId, questId) } }.awaitAll()

        assertThat(responses.map { it.statusCode.value() }.sorted()).containsExactly(200, 409, 409, 409, 409, 409)
        assertThat(responses.mapNotNull { it.body as? QuestClaimResponseDto }.single().rewards)
            .containsExactly(QuestSummaryRewardDto("CHEST", chestId("forest_chest"), "forest_chest", 1))
        assertThat(userChestCount()).isEqualTo(1L)
        assertThat(userQuestState(questId)).isEqualTo("COMPLETED")
    }

    @Test
    @DisplayName("조건을_채우기_전의_claim_은_422_이고_아무것도_바꾸지_않는다")
    fun claimBeforeConditionIsUnprocessable() = runBlocking<Unit> {
        val questId = adventureQuestId("forest")
        finishAdventure("forest", leaveLastActive = true)

        val response = controller().claimQuest(userId, questId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(response.body).isEqualTo("quest $questId is not completed yet")
        assertThat(queryLong("SELECT COUNT(*) FROM user_quests WHERE user_id = $userId")).isZero()
        assertThat(userChestCount()).isZero()
        assertThat(questService().findMyQuests(userId).single { it.questId == questId }.claimable).isFalse()
    }

    @Test
    @DisplayName("상자_지급이_실패하면_claim_이_rollback_되어_다시_claim_할_수_있다")
    fun failingGrantorRollsBackClaim() = runBlocking<Unit> {
        val questId = adventureQuestId("forest")
        finishAdventure("forest")
        // Grants for real, then fails, so the rollback has to undo an inserted user_chests row too.
        val realGrantor = ChestRewardGrantor(chestRepository)
        val failingGrantor = object : RewardGrantor by realGrantor {
            override suspend fun grant(userId: Long, reward: Reward) {
                realGrantor.grant(userId, reward)
                throw RewardNotGrantableException(reward, "test failure after granting")
            }
        }

        assertThrows<RewardNotGrantableException> { questService(grantors(failingGrantor)).claimQuest(userId, questId) }

        assertThat(userQuestState(questId)).isEqualTo("IN_PROGRESS")
        assertThat(userChestCount()).isZero()

        assertThat(controller().claimQuest(userId, questId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(controller().claimQuest(userId, questId).statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(userChestCount()).isEqualTo(1L)
    }

    @Test
    @DisplayName("DEPRECATED_인_퀘스트_5_10_은_목록에_없고_행이_생기지_않으며_claim_하면_404_다")
    fun deprecatedQuestsAreHidden() = runBlocking<Unit> {
        val deprecatedIds = (5L..10L).toList()
        assertThat(queryLong("SELECT COUNT(*) FROM quests WHERE id BETWEEN 5 AND 10 AND access_type = 'DEPRECATED'")).isEqualTo(6L)
        // Their stage conditions are met, which used to grant them.
        finishAdventure("forest")
        finishAdventure("fortress")
        val service = questService()

        assertThat(service.findMyQuests(userId).map { it.questId }).doesNotContainAnyElementsOf(deprecatedIds)
        assertThat(service.checkQuestsWithRewards(userId).map { it.questId }).doesNotContainAnyElementsOf(deprecatedIds)
        assertThat(queryLong("SELECT COUNT(*) FROM user_quests WHERE user_id = $userId AND quest_id BETWEEN 5 AND 10")).isZero()
        deprecatedIds.forEach { questId ->
            assertThat(controller(service).claimQuest(userId, questId).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
        assertThat(controller(service).claimQuest(userId, Long.MAX_VALUE).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(queryLong("SELECT COUNT(*) FROM user_magics WHERE user_id = $userId")).isZero()
    }

    @Test
    @DisplayName("숲_모험을_끝내면_퀘스트_11_이_claimable_이고_claim_하면_forest_chest_를_받으며_열면_grass_외형만_주고_고를_수_있다")
    fun forestAdventureEndToEnd() = runBlocking<Unit> {
        claimOpenAndSelect(adventureName = "forest", chestKey = "forest_chest", appearanceKey = "grass")
    }

    @Test
    @DisplayName("요새_모험을_끝내면_퀘스트_12_가_claimable_이고_claim_하면_fortress_chest_를_받으며_열면_golem_외형만_주고_고를_수_있다")
    fun fortressAdventureEndToEnd() = runBlocking<Unit> {
        claimOpenAndSelect(adventureName = "fortress", chestKey = "fortress_chest", appearanceKey = "golem")
    }

    /**
     * The whole player path on the real seed: the adventure's quest is listed `MANUAL` and not
     * claimable, finishing the adventure makes it claimable, the claim answers 200 with the chest,
     * the chest lists its single `APPEARANCE` reward, opening it grants only that appearance, and the
     * player can then select it.
     */
    private suspend fun claimOpenAndSelect(adventureName: String, chestKey: String, appearanceKey: String) {
        val questId = adventureQuestId(adventureName)
        val chestRowId = chestId(chestKey)
        val service = questService()
        val chestService = ChestService(chestRepository, registry(), transactionalOperator)

        val before = service.findMyQuests(userId).single { it.questId == questId }
        assertThat(before.claimMode).isEqualTo("MANUAL")
        assertThat(before.claimable).isFalse()

        finishAdventure(adventureName)
        val met = service.findMyQuests(userId).single { it.questId == questId }
        assertThat(met.claimable).isTrue()
        assertThat(met.state).isEqualTo(QuestState.IN_PROGRESS)
        assertThat(met.progress).isEqualTo(met.requireValue)

        val response = controller(service).claimQuest(userId, questId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(jacksonObjectMapper().writeValueAsString(response.body))
            .isEqualTo("""{"rewards":[{"rewardType":"CHEST","rewardId":$chestRowId,"rewardKey":"$chestKey","amount":1}]}""")

        val claimed = service.findMyQuests(userId).single { it.questId == questId }
        assertThat(claimed.state).isEqualTo(QuestState.COMPLETED)
        assertThat(claimed.claimable).isFalse()

        val chest = chestService.findMyChests(userId).single()
        assertThat(chest.chestKey).isEqualTo(chestKey)
        val expectedContents = listOf(ChestRewardDto("APPEARANCE", appearanceId(appearanceKey), appearanceKey, 1))
        assertThat(chest.rewards).isEqualTo(expectedContents)

        assertThat(chestService.openChest(userId, chest.id)).isEqualTo(ChestOpenResult.Opened(expectedContents))
        assertThat(chestService.findMyChests(userId)).isEmpty()
        assertThat(queryLong("SELECT COUNT(*) FROM user_magics WHERE user_id = $userId")).isZero()
        assertThat(queryString("SELECT string_agg(a.key, ',') FROM user_appearances ua JOIN appearances a ON a.id = ua.appearance_id WHERE ua.user_id = $userId"))
            .isEqualTo(appearanceKey)
        assertThat(AppearanceService(appearanceRepository).selectAppearance(userId, appearanceKey))
            .isEqualTo(AppearanceSelection.Selected(appearanceKey))
        assertThat(queryString("SELECT appearance FROM users WHERE id = $userId")).isEqualTo(appearanceKey)
    }

    companion object {
        private const val DATABASE_NAME = "quest_claim_it"
    }
}
