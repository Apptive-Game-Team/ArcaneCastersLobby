package com.wordonline.matching.progression

import com.wordonline.matching.support.MigratedPostgresDatabase
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class FactionRewardCatalogPostgresIntegrationTest {
    private lateinit var db: DatabaseClient
    private lateinit var repository: ProgressionRepository
    private lateinit var service: ProgressionService
    private var user = 4000000L
    private val starters = mapOf(
        "HELL" to listOf("fire_shot", "rock_golem", "rock_mage", "wall_golem", "stone_gargoyle"),
        "NATURE" to listOf("chain_lightning", "electric_spearman", "aqua_archer", "tree_golem", "thunder_spirit"),
    )
    private val levelRewards = mapOf(
        "HELL" to listOf("ember_spirit_swarm", "meteor_shower", "rock_golem", "magma_spirit", "frenzy_totem",
            "dragon_tower", "earth_call", "crater_carrier", "evil_ent", "fire_lord_spirit"),
        "NATURE" to listOf("water_slime_swarm", "tornado_strike", "life_tree", "vine_spirit", "grass_generator",
            "tide_call", "overgrowth", "storm_rider", "vine_world", "sea_serpent"),
    )

    @BeforeAll
    fun setup(): Unit = runBlocking {
        val factory = MigratedPostgresDatabase.build("faction_reward_catalog_it", 125)
        db = DatabaseClient.create(factory)
        repository = ProgressionRepository(db)
        service = ProgressionService(repository, ProgressionProperties(),
            TransactionalOperator.create(R2dbcTransactionManager(factory)))
    }

    @BeforeEach
    fun newUser(): Unit = runBlocking {
        user++
        db.sql("INSERT INTO users(id) VALUES ($user)").fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun number(sql: String): Long = db.sql(sql)
        .map { row, _ -> row.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
    private suspend fun names(sql: String): List<String> = db.sql(sql)
        .map { row, _ -> row.get(0, String::class.java)!! }.all().collectList().awaitSingle()

    @Test
    fun `양 진영의 시작 카드와 덱은 실습 다섯 종을 각 세장씩 한번만 지급한다`(): Unit = runBlocking {
        for ((faction, expected) in starters) {
            newUser()
            service.choose(user, faction)
            service.choose(user, faction)
            assertThat(names("SELECT m.name FROM user_magics u JOIN magics m ON m.id=u.magic_id WHERE u.user_id=$user"))
                .containsExactlyInAnyOrderElementsOf(expected)
            assertThat(number("SELECT COUNT(*) FROM user_magics WHERE user_id=$user AND count=3")).isEqualTo(5)
            assertThat(names("""SELECT m.name FROM decks d JOIN deck_cards c ON c.deck_id=d.id
                JOIN magics m ON m.id=c.magic_id WHERE d.user_id=$user AND c.count=3"""))
                .containsExactlyInAnyOrderElementsOf(expected)
            assertThat(number("SELECT COUNT(*) FROM decks WHERE user_id=$user")).isEqualTo(1)
        }
    }

    @Test
    fun `시작과 모든 오레벨 간격의 보상 조회가 승인 표와 일치한다`(): Unit = runBlocking {
        assertThat(number("SELECT COUNT(*) FROM progression_magic_rewards")).isEqualTo(30)
        for ((faction, expected) in levelRewards) {
            newUser()
            service.choose(user, faction)
            val response = service.rewards(user)!!
            assertThat(response.levels.first().cards.map { it.magicName }).containsExactlyInAnyOrderElementsOf(starters[faction]!!)
            assertThat(response.levels.first().cards.map { it.amount }).containsOnly(3)
            for ((index, name) in expected.withIndex()) {
                val reward = response.levels.single { it.level == (index + 1) * 5 }.cards.single()
                assertThat(reward.magicName).isEqualTo(name)
                assertThat(reward.amount).isEqualTo(2)
            }
        }
    }

    @Test
    fun `시작과 십오레벨에 등록된 카드는 최초 해금부터 추첨 후보에 한번만 들어간다`(): Unit = runBlocking {
        val id = number("SELECT id FROM magics WHERE name='rock_golem'")
        repository.grantMagic(user, id, 2)
        for (level in listOf(1, 14, 15)) {
            val candidates = repository.eligibleMagics(user, level)
            assertThat(candidates.count { it.name == "rock_golem" }).isEqualTo(1)
            assertThat(candidates.map { it.id }).doesNotHaveDuplicates()
        }
        repository.grantMagic(user, id, 1)
        assertThat(repository.eligibleMagics(user, 15).map { it.name }).doesNotContain("rock_golem")
    }

    @Test
    fun `십오레벨 돌 골렘 지급과 수동 수령 재시도는 시작 카드의 세장 상한을 유지한다`(): Unit = runBlocking {
        for (manual in listOf(false, true)) {
            newUser()
            service.choose(user, "HELL", manual)
            db.sql("UPDATE player_progression SET xp=${ProgressionPolicy.threshold(15)} WHERE user_id=$user")
                .fetch().rowsUpdated().awaitSingle()
            if (manual) {
                val pending = service.pendingRewards(user, true).rewards.single { it.level == 15 }
                assertThat(pending.rewards.single { it.rewardType == "MAGIC" }.rewardKey).isEqualTo("rock_golem")
                val receipt = service.claimReward(user, pending.id, true)!!
                assertThat(receipt.rewards.single { it.rewardType == "MAGIC" }.amount).isZero()
                assertThat(service.claimReward(user, pending.id, true)!!.rewards).isEqualTo(receipt.rewards)
            } else {
                assertThat(service.sync(user).level).isEqualTo(15)
            }
            assertThat(number("""SELECT u.count FROM user_magics u JOIN magics m ON m.id=u.magic_id
                WHERE u.user_id=$user AND m.name='rock_golem'""")).isEqualTo(3)
        }
    }

    @Test
    fun `보상표 마이그레이션 재실행은 기존 카드 덱과 미수령 보상을 보존한다`(): Unit = runBlocking {
        val factory = MigratedPostgresDatabase.build("faction_reward_upgrade_it", 125, throughVersion = 124)
        val beforeDb = DatabaseClient.create(factory)
        val beforeService = ProgressionService(ProgressionRepository(beforeDb), ProgressionProperties(),
            TransactionalOperator.create(R2dbcTransactionManager(factory)))
        val owner = 4100000L
        beforeDb.sql("INSERT INTO users(id) VALUES ($owner)").fetch().rowsUpdated().awaitSingle()
        beforeService.choose(owner, "HELL", true)
        beforeDb.sql("UPDATE player_progression SET xp=${ProgressionPolicy.threshold(5)} WHERE user_id=$owner")
            .fetch().rowsUpdated().awaitSingle()
        val pending = beforeService.pendingRewards(owner, true).rewards.single { it.level == 5 }
        val selections = listOf(
            "SELECT * FROM user_magics WHERE user_id=$owner",
            "SELECT * FROM decks WHERE user_id=$owner",
            "SELECT * FROM deck_cards WHERE deck_id IN (SELECT id FROM decks WHERE user_id=$owner)",
            "SELECT * FROM player_progression WHERE user_id=$owner",
            "SELECT * FROM progression_reward_claims WHERE user_id=$owner",
            "SELECT * FROM progression_reward_items WHERE claim_id IN (SELECT id FROM progression_reward_claims WHERE user_id=$owner)",
        )
        suspend fun snapshots(): List<String> = selections.map { sql ->
            beforeDb.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM ($sql) t")
                .map { row, _ -> row.get(0, String::class.java)!! }.one().awaitSingle()
        }
        val before = snapshots()
        val directory = File(System.getenv(MigratedPostgresDatabase.MIGRATION_DIRECTORY_VARIABLE) ?: "../database/migration")
        val migration = directory.listFiles()!!.single { it.name.startsWith("V125_") }.readText()
        repeat(2) {
            val connection = Mono.from(factory.create()).awaitSingle()
            try {
                Flux.from(connection.createStatement("BEGIN;\n$migration\nCOMMIT;").execute())
                    .concatMap { Flux.from(it.rowsUpdated) }.then().awaitSingleOrNull()
            } finally { Mono.from(connection.close()).awaitSingleOrNull() }
            assertThat(snapshots()).isEqualTo(before)
        }
        assertThat(beforeService.claimReward(owner, pending.id, true)!!.rewards.single { it.rewardType == "MAGIC" }.rewardKey)
            .isEqualTo("rock_mage")
    }
}
