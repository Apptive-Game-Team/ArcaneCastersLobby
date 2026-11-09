package com.wordonline.matching.progression

import com.wordonline.matching.chest.repository.ChestRepository
import com.wordonline.matching.chest.service.ChestOpenResult
import com.wordonline.matching.chest.service.ChestService
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
class ManualProgressionRewardsPostgresIntegrationTest {
    private lateinit var db: DatabaseClient
    private lateinit var repository: ProgressionRepository
    private lateinit var transaction: TransactionalOperator
    private lateinit var service: ProgressionService
    private var user = 2000000L
    private val enabled = RewardClaimPolicy(RewardsProperties(manualClaimsEnabled = true))

    @BeforeAll
    fun setup(): Unit = runBlocking {
        val factory = MigratedPostgresDatabase.build("manual_progression_rewards_it", 125)
        db = DatabaseClient.create(factory)
        repository = ProgressionRepository(db)
        transaction = TransactionalOperator.create(R2dbcTransactionManager(factory))
        service = ProgressionService(repository, ProgressionProperties(), transaction)
    }

    @BeforeEach
    fun newUser(): Unit = runBlocking {
        user++
        exec("INSERT INTO users(id) VALUES ($user)")
    }

    private suspend fun exec(sql: String) { db.sql(sql).fetch().rowsUpdated().awaitSingle() }
    private suspend fun number(sql: String): Long = db.sql(sql).map { r, _ -> r.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
    private suspend fun levelFive(): PendingProgressionReward {
        service.choose(user, "HELL", true)
        exec("UPDATE player_progression SET xp=${ProgressionPolicy.threshold(5)} WHERE user_id=$user")
        return service.pendingRewards(user, true).rewards.single { it.level == 5 }
    }
    private suspend fun magicCount(): Long = number("SELECT COALESCE(SUM(count),0) FROM user_magics WHERE user_id=$user AND magic_id=(SELECT id FROM magics WHERE name='ember_spirit_swarm')")

    @Test
    fun `수동 모드는 레벨과 시작 덱만 즉시 반영하고 레벨 보상과 오늘 상자를 보관한다`(): Unit = runBlocking {
        val reward = levelFive()
        assertThat(number("SELECT level FROM player_progression WHERE user_id=$user")).isEqualTo(5)
        assertThat(number("SELECT SUM(count) FROM user_magics WHERE user_id=$user")).isEqualTo(15)
        assertThat(number("SELECT COUNT(*) FROM decks WHERE user_id=$user")).isEqualTo(1)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isZero()
        val pending = service.pendingRewards(user, true)
        assertThat(pending.enabled).isTrue()
        assertThat(pending.baselineLevel).isEqualTo(1)
        assertThat(pending.claimedLevels).isEmpty()
        assertThat(pending.rewards).hasSize(5)
        assertThat(reward.rewards.map { it.rewardType }).containsExactly("MAGIC", "CHEST")
        assertThat(reward.rewards.last().rewardSlots).isEqualTo(6)
        assertThat(service.sync(user, true).grantedChests).isZero()
        assertThat(service.pendingRewards(user, true).rewards.map { it.id }).isEqualTo(pending.rewards.map { it.id })
    }

    @Test
    fun `동시 수령과 응답 유실 재시도는 같은 실제 영수증을 반환하고 한번만 지급한다`(): Unit = runBlocking {
        val reward = levelFive()
        val results = (1..6).map { async { service.claimReward(user, reward.id, true)!! } }.awaitAll()
        assertThat(results.count { !it.alreadyClaimed }).isEqualTo(1)
        assertThat(results.map { it.rewards }.distinct()).hasSize(1)
        assertThat(results.first().rewards.map { it.amount }).containsExactly(2, 1)
        assertThat(magicCount()).isEqualTo(2)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
        assertThat(service.claimReward(user, reward.id, true)!!.rewards).isEqualTo(results.first().rewards)
        assertThat(service.pendingRewards(user, true).claimedLevels).containsExactly(5)
    }

    @Test
    fun `마법 영수증은 보유 상한을 반영한 실제 증가량 0과 1과 2를 저장한다`(): Unit = runBlocking {
        for ((owned, delta) in listOf(0 to 2, 2 to 1, 3 to 0, 5 to 0)) {
            newUser()
            val reward = levelFive()
            exec("INSERT INTO user_magics(user_id,magic_id,count) SELECT $user,id,$owned FROM magics WHERE name='ember_spirit_swarm'")
            val result = service.claimReward(user, reward.id, true)!!
            assertThat(result.rewards.single { it.rewardType == "MAGIC" }.amount).isEqualTo(delta)
            assertThat(magicCount()).isEqualTo((owned + delta).toLong())
            assertThat(service.claimReward(user, reward.id, true)!!.rewards).isEqualTo(result.rewards)
        }
    }

    @Test
    fun `다른 사용자의 보상은 조회하거나 수령할 수 없다`(): Unit = runBlocking {
        val reward = levelFive()
        val owner = user
        newUser()
        assertThat(service.claimReward(user, reward.id, true)).isNull()
        assertThat(service.pendingRewards(user, true).rewards).isEmpty()
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$owner")).isZero()
    }

    @Test
    fun `상자 지급 실패는 마법과 영수증과 수령 상태를 함께 되돌린다`(): Unit = runBlocking {
        val reward = levelFive()
        exec("""CREATE FUNCTION reject_manual_chest() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN IF NEW.user_id=$user THEN RAISE EXCEPTION 'test grant failure'; END IF; RETURN NEW; END ${'$'}${'$'}""")
        exec("CREATE TRIGGER reject_manual_chest BEFORE INSERT ON user_chests FOR EACH ROW EXECUTE FUNCTION reject_manual_chest()")
        try {
            var failure: Exception? = null
            try { service.claimReward(user, reward.id, true) } catch (e: Exception) { failure = e }
            assertThat(failure).isNotNull()
            assertThat(magicCount()).isZero()
            assertThat(number("SELECT COUNT(*) FROM progression_reward_claims WHERE id=${reward.id} AND claimed_at IS NOT NULL")).isZero()
            assertThat(number("SELECT COUNT(*) FROM progression_reward_items WHERE claim_id=${reward.id} AND granted_amount IS NOT NULL")).isZero()
        } finally {
            exec("DROP TRIGGER reject_manual_chest ON user_chests")
            exec("DROP FUNCTION reject_manual_chest()")
        }
        assertThat(service.claimReward(user, reward.id, true)!!.alreadyClaimed).isFalse()
        assertThat(magicCount()).isEqualTo(2)
    }

    @Test
    fun `이미 자동 소유한 source는 대기 보상을 재지급하지 않고 영수증을 0으로 확정한다`(): Unit = runBlocking {
        val reward = levelFive()
        repository.issueChest(user, "level:5", 6)
        val result = service.claimReward(user, reward.id, true)!!
        assertThat(result.rewards.map { it.amount }).containsExactly(0, 0)
        assertThat(magicCount()).isZero()
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
        assertThat(service.claimReward(user, reward.id, true)!!.rewards).isEqualTo(result.rewards)
    }

    @Test
    fun `수령은 이후 지급표가 바뀌어도 발급 당시 마법과 상자 칸 수를 사용한다`(): Unit = runBlocking {
        val reward = levelFive()
        val magic = reward.rewards.single { it.rewardType == "MAGIC" }
        exec("DELETE FROM progression_magic_rewards WHERE faction='HELL' AND unlock_level=5")
        try {
            val receipt = service.claimReward(user, reward.id, true)!!
            assertThat(receipt.rewards).isEqualTo(reward.rewards)
            assertThat(magicCount()).isEqualTo(2)
            assertThat(number("SELECT reward_slots FROM user_chests WHERE user_id=$user AND reward_source='level:5'")).isEqualTo(6)
        } finally {
            exec("INSERT INTO progression_magic_rewards(magic_id,faction,unlock_level) VALUES (${magic.rewardId},'HELL',5)")
        }
    }

    @Test
    fun `기존 자동 지급 기준선 이하 레벨과 이미 소유한 source는 새 pending으로 만들지 않는다`(): Unit = runBlocking {
        service.choose(user, "HELL")
        exec("UPDATE player_progression SET xp=${ProgressionPolicy.threshold(5)} WHERE user_id=$user")
        service.sync(user)
        assertThat(service.pendingRewards(user, true).baselineLevel).isEqualTo(5)
        assertThat(service.pendingRewards(user, true).rewards).isEmpty()
        assertThat(number("SELECT SUM(count) FROM user_magics WHERE user_id=$user")).isEqualTo(17)
        repository.issueChest(user, "level:6", 3)
        exec("UPDATE player_progression SET xp=${ProgressionPolicy.threshold(6)} WHERE user_id=$user")
        val pending = service.pendingRewards(user, true)
        assertThat(pending.rewards).isEmpty()
        assertThat(pending.claimedLevels).contains(6)
    }

    @Test
    fun `flag 비활성과 capability 없는 구버전은 기존 pending을 동일 경로로 자동 수령한다`(): Unit = runBlocking {
        for (policy in listOf(RewardClaimPolicy(), enabled)) {
            newUser()
            levelFive()
            val manual = policy.manual(if (policy === enabled) null else "manual-v1")
            assertThat(manual).isFalse()
            val result = service.sync(user, manual)
            assertThat(result.grantedChests).isEqualTo(5)
            assertThat(service.pendingRewards(user, manual).rewards).isEmpty()
            assertThat(magicCount()).isEqualTo(2)
            assertThat(service.sync(user, manual).grantedChests).isZero()
        }
    }

    @Test
    fun `수동과 구버전 동시 동기화와 수령도 source마다 한번만 지급한다`(): Unit = runBlocking {
        val reward = levelFive()
        listOf(async { service.sync(user, true) }, async { service.sync(user, false) },
            async { service.claimReward(user, reward.id, true) }).awaitAll()
        assertThat(magicCount()).isEqualTo(2)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(5)
        assertThat(service.pendingRewards(user, true).rewards).isEmpty()
        assertThat(service.claimReward(user, reward.id, true)!!.alreadyClaimed).isTrue()
    }

    @Test
    fun `수동 모드 상자 개봉의 내부 sync는 미수령 보상을 자동 수령하지 않는다`(): Unit = runBlocking {
        levelFive()
        repository.issueChest(user, "test:open", 3)
        val id = number("SELECT id FROM user_chests WHERE user_id=$user AND reward_source='test:open'")
        val chests = ChestService(ChestRepository(db), QuestRegistry(emptyList(), emptyList()), transaction, repository, service)
        assertThat(chests.openChest(user, id, true)).isInstanceOf(ChestOpenResult.Opened::class.java)
        assertThat(service.pendingRewards(user, true).rewards).hasSize(5)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
    }
}
