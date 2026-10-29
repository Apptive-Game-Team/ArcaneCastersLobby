package com.wordonline.matching.progression

import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.chest.service.ChestService
import com.wordonline.matching.chest.service.ChestOpenResult
import com.wordonline.matching.quest.service.QuestRegistry
import com.wordonline.matching.support.MigratedPostgresDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class ProgressionPostgresIntegrationTest {
    private lateinit var db: DatabaseClient
    private lateinit var repository: ProgressionRepository
    private lateinit var transaction: TransactionalOperator
    private lateinit var service: ProgressionService
    private var user = 1000000L

    @BeforeAll
    fun setup(): Unit = runBlocking {
        val factory = MigratedPostgresDatabase.build("progression_rewards_it", 121)
        db = DatabaseClient.create(factory)
        repository = ProgressionRepository(db)
        transaction = TransactionalOperator.create(R2dbcTransactionManager(factory))
        service = ProgressionService(repository, ProgressionProperties(cardProbability = 1.0, currencyMin = 10, currencyMax = 10), transaction)
    }
    @BeforeEach
    fun newUser(): Unit = runBlocking {
        user++
        exec("INSERT INTO users(id) VALUES ($user)")
    }
    private suspend fun exec(sql: String) { db.sql(sql).fetch().rowsUpdated().awaitSingle() }
    private suspend fun number(sql: String): Long = db.sql(sql).map { r, _ -> r.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
    private fun chests() = ChestService(ChestRepository(db), QuestRegistry(emptyList(), emptyList()), transaction, repository, service)

    @Test
    fun `서버에서 끝난 매칭 경기만 한 번 반영하고 친선전과 연습은 제외한다`(): Unit = runBlocking {
        service.choose(user, "HELL")
        for ((suffix, type) in listOf("ranked" to "PVP", "friend" to "PVP", "practice" to "Practice")) {
            val sid = "$user-$suffix"
            if (suffix != "friend") repository.registerMatch(sid, user)
            val gameId = number("""INSERT INTO statistic_games(win_user_id,loss_user_id,duration,game_type,server_version,event_schema_version)
                VALUES ($user,0,180,'$type','test',1) RETURNING id""")
            exec("""INSERT INTO statistic_game_sessions(session_id,left_user_id,right_user_id,game_type,server_domain,server_port,
                server_instance_id,server_version,status,statistic_game_id,ended_at)
                VALUES ('$sid',$user,0,'$type','localhost',1,'test','test','COMPLETED',$gameId,now())""")
        }
        val results = listOf(async { service.sync(user) }, async { service.sync(user) }).awaitAll()
        assertThat(results.sumOf { it.gainedXp }).isEqualTo(100)
        assertThat(service.sync(user).xp).isEqualTo(100)
        assertThat(number("SELECT COUNT(*) FROM progression_match_rewards WHERE user_id=$user")).isEqualTo(1)
    }

    @Test
    fun `항복과 이탈 패배는 경험치를 지급하지 않고 승자는 정상 지급한다`(): Unit = runBlocking {
        service.choose(user, "HELL")
        for ((index, reason) in listOf("SURRENDER", "DISCONNECT").withIndex()) {
            val sid = "$user-forfeit-$index"
            repository.registerMatch(sid, user)
            val gameId = number("""INSERT INTO statistic_games(win_user_id,loss_user_id,duration,game_type,server_version,event_schema_version)
                VALUES (0,$user,180,'PVP','test',1) RETURNING id""")
            exec("""INSERT INTO statistic_game_sessions(session_id,left_user_id,right_user_id,game_type,server_domain,server_port,
                server_instance_id,server_version,status,end_reason,statistic_game_id,ended_at)
                VALUES ('$sid',$user,0,'PVP','localhost',1,'test','test','COMPLETED','$reason',$gameId,now())""")
        }
        assertThat(service.sync(user).xp).isZero()
        assertThat(number("SELECT COUNT(*) FROM progression_match_rewards WHERE user_id=$user AND xp=0")).isEqualTo(2)
        assertThat(ProgressionPolicy.xp(0, 0, user, "COMPLETED", "SURRENDER")).isEqualTo(100)
    }

    @Test
    fun `지난 일일 상자는 보존하고 오늘 상자는 한번만 지급한다`(): Unit = runBlocking {
        service.choose(user, "NATURE")
        exec("UPDATE user_chests SET reward_source='daily:2000-01-01' WHERE user_id=$user")
        assertThat(service.sync(user).grantedChests).isEqualTo(1)
        assertThat(service.sync(user).grantedChests).isZero()
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user AND opened_at IS NULL")).isEqualTo(2)
    }

    @Test
    fun `동시 세력 선택과 일일 상자는 한 번만 지급된다`(): Unit = runBlocking {
        listOf(async { service.choose(user, "HELL") }, async { service.choose(user, "HELL") }).awaitAll()
        assertThat(number("SELECT SUM(count) FROM user_magics WHERE user_id=$user")).isEqualTo(15)
        assertThat(number("SELECT COUNT(*) FROM decks WHERE user_id=$user")).isEqualTo(1)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
        assertThat(service.choose(user, "NATURE")).isNull()
        service.sync(user)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
    }

    @Test
    fun `여러 레벨을 한번에 통과해도 고정 보상과 상자는 중복되지 않는다`(): Unit = runBlocking {
        service.choose(user, "NATURE")
        exec("UPDATE player_progression SET xp=12500 WHERE user_id=$user")
        assertThat(service.sync(user).level).isEqualTo(50)
        assertThat(number("SELECT SUM(count) FROM user_magics WHERE user_id=$user")).isEqualTo(35)
        assertThat(number("SELECT SUM(reward_slots) FROM user_chests WHERE user_id=$user AND reward_source LIKE 'level:%'")).isEqualTo(178)
        service.sync(user)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(50)
    }

    @Test
    fun `같은 상자와 다른 상자를 동시에 열어도 마지막 카드 한장만 지급한다`(): Unit = runBlocking {
        service.choose(user, "HELL")
        exec("INSERT INTO user_magics(user_id,magic_id,count) SELECT $user,id,3 FROM magics WHERE purpose='PLAYER' ON CONFLICT(user_id,magic_id) DO UPDATE SET count=3")
        exec("UPDATE user_magics SET count=2 WHERE user_id=$user AND magic_id=(SELECT id FROM magics WHERE name='chain_lightning')")
        repository.issueChest(user, "test:second", 3)
        val ids = db.sql("SELECT id FROM user_chests WHERE user_id=$user ORDER BY id").map { r,_ -> r.get(0, Number::class.java)!!.toLong() }.all().collectList().awaitSingle()
        val chestService = chests()
        val results = listOf(async { chestService.openChest(user, ids[0]) }, async { chestService.openChest(user, ids[0]) }, async { chestService.openChest(user, ids[1]) }).awaitAll()
        assertThat(results.count { it is ChestOpenResult.Opened }).isEqualTo(2)
        assertThat(results.count { it is ChestOpenResult.AlreadyOpened }).isEqualTo(1)
        assertThat(number("SELECT MAX(count) FROM user_magics WHERE user_id=$user")).isEqualTo(3)
        assertThat(number("SELECT currency FROM player_progression WHERE user_id=$user")).isEqualTo(50)
    }

    @Test
    fun `해금 전 최상위 카드는 추첨하지 않고 기존 초과 보유량은 보존한다`(): Unit = runBlocking {
        service.choose(user, "HELL")
        exec("UPDATE user_magics SET count=5 WHERE user_id=$user AND magic_id=(SELECT id FROM magics WHERE name='fire_shot')")
        assertThat(repository.eligibleMagics(user, 1).map { it.name }).doesNotContain("meteor_shower", "tornado_strike", "fire_lord_spirit", "sea_serpent")
        assertThat(repository.eligibleMagics(user, 10).map { it.name }).contains("meteor_shower", "tornado_strike")
        repository.grantMagic(user, number("SELECT id FROM magics WHERE name='fire_shot'"), 2)
        assertThat(number("SELECT count FROM user_magics WHERE user_id=$user AND magic_id=(SELECT id FROM magics WHERE name='fire_shot')")).isEqualTo(5)
    }
}
