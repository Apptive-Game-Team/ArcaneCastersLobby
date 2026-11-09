package com.wordonline.matching.progression

import io.r2dbc.spi.Row
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant

data class ProgressionState(val faction: String?, val xp: Long, val level: Int, val currency: Long, val baselineLevel: Int = level)
data class ProgressionMagic(val id: Long, val name: String)
data class ProgressionMatch(
    val id: Long, val winner: Long?, val loser: Long?, val status: String, val reason: String?,
    val rankedEndDate: LocalDate?,
)

@Repository
class ProgressionRepository(private val db: DatabaseClient) {
    private fun query(sql: String, vararg values: Pair<String, Any>): DatabaseClient.GenericExecuteSpec {
        var spec = db.sql(sql)
        values.forEach { (key, value) -> spec = spec.bind(key, value) }
        return spec
    }
    private fun Row.long(name: String): Long = get(name, Number::class.java)!!.toLong()

    // 같은 사용자의 세력 선택, 레벨 보상, 서로 다른 상자 개봉까지 직렬화한다.
    suspend fun lock(userId: Long): ProgressionState {
        query("INSERT INTO player_progression(user_id) VALUES (:uid) ON CONFLICT DO NOTHING", "uid" to userId)
            .fetch().rowsUpdated().awaitSingle()
        return query("SELECT * FROM player_progression WHERE user_id=:uid FOR UPDATE", "uid" to userId)
            .map { row, _ -> ProgressionState(row.get("faction", String::class.java), row.long("xp"), row.long("level").toInt(), row.long("currency"), row.long("reward_baseline_level").toInt()) }
            .one().awaitSingle()
    }

    suspend fun choose(userId: Long, faction: String) {
        query("UPDATE player_progression SET faction=:faction WHERE user_id=:uid", "uid" to userId, "faction" to faction)
            .fetch().rowsUpdated().awaitSingle()
    }
    suspend fun save(userId: Long, xp: Long, level: Int) {
        query("UPDATE player_progression SET xp=:xp, level=:level WHERE user_id=:uid", "uid" to userId, "xp" to xp, "level" to level)
            .fetch().rowsUpdated().awaitSingle()
    }
    suspend fun addCurrency(userId: Long, amount: Int) {
        query("UPDATE player_progression SET currency=currency+:amount WHERE user_id=:uid", "uid" to userId, "amount" to amount)
            .fetch().rowsUpdated().awaitSingle()
    }
    suspend fun advanceBaseline(userId: Long, level: Int) {
        query("UPDATE player_progression SET reward_baseline_level=GREATEST(reward_baseline_level,:level) WHERE user_id=:uid",
            "uid" to userId, "level" to level).fetch().rowsUpdated().awaitSingle()
    }

    suspend fun grantMagicDelta(userId: Long, magicId: Long, amount: Int): Int {
        query("INSERT INTO user_magics(user_id,magic_id,count) VALUES (:uid,:magic,0) ON CONFLICT DO NOTHING",
            "uid" to userId, "magic" to magicId).fetch().rowsUpdated().awaitSingle()
        val before = query("SELECT count FROM user_magics WHERE user_id=:uid AND magic_id=:magic FOR UPDATE",
            "uid" to userId, "magic" to magicId).map { r, _ -> r.long("count").toInt() }.one().awaitSingle()
        grantMagic(userId, magicId, amount)
        return maxOf(before, minOf(3, before + amount)) - before
    }

    suspend fun ownsSource(userId: Long, source: String): Boolean =
        query("SELECT EXISTS(SELECT 1 FROM user_chests WHERE user_id=:uid AND reward_source=:source) AS owned",
            "uid" to userId, "source" to source).map { r, _ -> r.get("owned", java.lang.Boolean::class.java)!!.booleanValue() }.one().awaitSingle()

    suspend fun enqueueReward(userId: Long, source: String, level: Int?, magics: List<ProgressionMagic>, slots: Int) {
        if (ownsSource(userId, source)) return
        var spec = query("""INSERT INTO progression_reward_claims(user_id,source,level) VALUES (:uid,:source,:level)
            ON CONFLICT DO NOTHING RETURNING id""", "uid" to userId, "source" to source)
        spec = if (level == null) spec.bindNull("level", Integer::class.java) else spec.bind("level", level)
        val id = spec.map { r, _ -> r.long("id") }.one().awaitSingleOrNull() ?: return
        magics.forEachIndexed { index, magic ->
            query("""INSERT INTO progression_reward_items(claim_id,position,reward_type,magic_id,reward_key,amount)
                VALUES (:id,:position,'MAGIC',:magic,:key,2)""", "id" to id, "position" to index,
                "magic" to magic.id, "key" to magic.name).fetch().rowsUpdated().awaitSingle()
        }
        val inserted = query("""INSERT INTO progression_reward_items(claim_id,position,reward_type,chest_id,reward_key,amount,reward_slots)
            SELECT :id,:position,'CHEST',id,key,1,:slots FROM chests WHERE key='progression_chest'""",
            "id" to id, "position" to magics.size, "slots" to slots).fetch().rowsUpdated().awaitSingle()
        check(inserted == 1L) { "Missing progression chest catalog" }
    }

    data class RewardClaim(val id: Long, val source: String, val level: Int?, val createdAt: Instant, val claimed: Boolean)
    data class RewardItem(val position: Int, val reward: ProgressionClaimReward, val grantedAmount: Int?)

    private fun Row.rewardClaim() = RewardClaim(long("id"), get("source", String::class.java)!!,
        get("level", Number::class.java)?.toInt(), get("created_at", java.time.OffsetDateTime::class.java)!!.toInstant(),
        get("claimed_at") != null)

    suspend fun pendingRewardClaims(userId: Long): List<RewardClaim> =
        query("SELECT * FROM progression_reward_claims WHERE user_id=:uid AND claimed_at IS NULL ORDER BY id", "uid" to userId)
            .map { r, _ -> r.rewardClaim() }.all().asFlow().toList()

    suspend fun lockRewardClaim(userId: Long, claimId: Long): RewardClaim? =
        query("SELECT * FROM progression_reward_claims WHERE user_id=:uid AND id=:id FOR UPDATE", "uid" to userId, "id" to claimId)
            .map { r, _ -> r.rewardClaim() }.one().awaitSingleOrNull()

    suspend fun rewardItems(claimId: Long): List<RewardItem> =
        query("SELECT *,COALESCE(magic_id,chest_id) AS reward_id FROM progression_reward_items WHERE claim_id=:id ORDER BY position", "id" to claimId)
            .map { r, _ -> RewardItem(r.long("position").toInt(), ProgressionClaimReward(r.get("reward_type", String::class.java)!!,
                r.long("reward_id"), r.get("reward_key", String::class.java)!!, r.long("amount").toInt(), r.long("reward_slots").toInt()),
                r.get("granted_amount", Number::class.java)?.toInt()) }.all().asFlow().toList()

    suspend fun grantSnapshotChest(userId: Long, source: String, reward: ProgressionClaimReward): Boolean =
        query("""INSERT INTO user_chests(user_id,chest_id,reward_source,reward_slots) VALUES (:uid,:chest,:source,:slots)
            ON CONFLICT (user_id,reward_source) WHERE reward_source IS NOT NULL DO NOTHING""",
            "uid" to userId, "chest" to reward.rewardId, "source" to source, "slots" to reward.rewardSlots).fetch().rowsUpdated().awaitSingle() == 1L

    suspend fun saveReceipt(claimId: Long, position: Int, amount: Int) {
        query("UPDATE progression_reward_items SET granted_amount=:amount WHERE claim_id=:id AND position=:position",
            "id" to claimId, "position" to position, "amount" to amount).fetch().rowsUpdated().awaitSingle()
    }
    suspend fun finishRewardClaim(claimId: Long) {
        query("UPDATE progression_reward_claims SET claimed_at=now() WHERE id=:id", "id" to claimId).fetch().rowsUpdated().awaitSingle()
    }
    suspend fun claimedLevels(userId: Long): List<Int> =
        query("""SELECT level FROM progression_reward_claims WHERE user_id=:uid AND claimed_at IS NOT NULL AND level IS NOT NULL
            UNION SELECT substring(reward_source from 7)::int AS level FROM user_chests
            WHERE user_id=:uid AND reward_source ~ '^level:([2-9]|[1-4][0-9]|50)$' ORDER BY level""", "uid" to userId)
            .map { r, _ -> r.long("level").toInt() }.all().asFlow().toList()
    suspend fun fixedMagics(faction: String, level: Int): List<ProgressionMagic> =
        query("""SELECT m.id,m.name FROM progression_magic_rewards r JOIN magics m ON m.id=r.magic_id
            WHERE r.faction=:faction AND r.unlock_level=:level ORDER BY m.id""", "faction" to faction, "level" to level)
            .map { r, _ -> ProgressionMagic(r.long("id"), r.get("name", String::class.java)!!) }.all().asFlow().toList()

    // 조회 화면은 지급표를 읽기만 하며 계정 생성이나 보상 지급을 수행하지 않는다.
    suspend fun faction(userId: Long): String? =
        query("SELECT faction FROM player_progression WHERE user_id=:uid AND faction IS NOT NULL", "uid" to userId)
            .map { r, _ -> r.get("faction", String::class.java)!! }.one().awaitSingleOrNull()

    suspend fun rewardCatalog(faction: String): List<ProgressionCatalogMagic> =
        query("""SELECT m.id,m.name,r.unlock_level FROM progression_magic_rewards r
            JOIN magics m ON m.id=r.magic_id WHERE r.faction=:faction ORDER BY r.unlock_level,m.id""", "faction" to faction)
            .map { r, _ -> ProgressionCatalogMagic(r.long("unlock_level").toInt(), r.long("id"), r.get("name", String::class.java)!!) }
            .all().asFlow().toList()

    suspend fun eligibleMagics(userId: Long, level: Int): List<ProgressionMagic> =
        query("""SELECT DISTINCT m.id,m.name FROM magics m
            LEFT JOIN progression_magic_rewards r ON r.magic_id=m.id
            LEFT JOIN user_magics u ON u.magic_id=m.id AND u.user_id=:uid
            WHERE m.purpose='PLAYER' AND COALESCE(r.unlock_level,1)<=:level AND COALESCE(u.count,0)<3
            ORDER BY m.id""", "uid" to userId, "level" to level)
            .map { r, _ -> ProgressionMagic(r.long("id"), r.get("name", String::class.java)!!) }.all().asFlow().toList()

    suspend fun grantMagic(userId: Long, magicId: Long, amount: Int) {
        // 기존 계정에 3장보다 많은 카드가 있어도 회수하지 않는다.
        query("""INSERT INTO user_magics(user_id,magic_id,count) VALUES (:uid,:magic,:amount)
            ON CONFLICT (user_id,magic_id) DO UPDATE
            SET count=GREATEST(user_magics.count,LEAST(3,user_magics.count+EXCLUDED.count))""",
            "uid" to userId, "magic" to magicId, "amount" to amount).fetch().rowsUpdated().awaitSingle()
    }
    suspend fun createStarterDeck(userId: Long, faction: String) {
        val deckId = query("""INSERT INTO decks(user_id,name) VALUES (:uid,:name) RETURNING id""",
            "uid" to userId, "name" to if (faction == "HELL") "Inferno" else "Nature")
            .map { r, _ -> r.long("id") }.one().awaitSingle()
        query("""INSERT INTO deck_cards(deck_id,magic_id,count)
            SELECT :deck,magic_id,3 FROM progression_magic_rewards WHERE faction=:faction AND unlock_level=1""",
            "deck" to deckId, "faction" to faction).fetch().rowsUpdated().awaitSingle()
        query("UPDATE users SET selected_deck_id=:deck WHERE id=:uid AND selected_deck_id IS NULL",
            "deck" to deckId, "uid" to userId).fetch().rowsUpdated().awaitSingle()
    }
    suspend fun issueChest(userId: Long, source: String, slots: Int): Boolean =
        query("""INSERT INTO user_chests(user_id,chest_id,reward_source,reward_slots)
            SELECT :uid,id,:source,:slots FROM chests WHERE key='progression_chest'
            ON CONFLICT (user_id,reward_source) WHERE reward_source IS NOT NULL DO NOTHING""",
            "uid" to userId, "source" to source, "slots" to slots).fetch().rowsUpdated().awaitSingle() == 1L

    suspend fun chestSlots(userId: Long, chestId: Long): Int =
        query("SELECT reward_slots FROM user_chests WHERE user_id=:uid AND id=:id", "uid" to userId, "id" to chestId)
            .map { r, _ -> r.long("reward_slots").toInt() }.one().awaitSingle()

    suspend fun registerMatch(sessionId: String, userId: Long, matchRequestedAt: Instant? = null) {
        if (userId <= 0) return
        if (matchRequestedAt == null) {
            query("INSERT INTO progression_matches(session_id,user_id) VALUES (:sid,:uid) ON CONFLICT DO NOTHING",
                "sid" to sessionId, "uid" to userId).fetch().rowsUpdated().awaitSingle()
        } else {
            // 세션 준비 응답이 늦어도 요청 이후 시작된 경기는 과거 경기로 제외하지 않는다.
            query("INSERT INTO progression_matches(session_id,user_id,created_at) VALUES (:sid,:uid,:requestedAt) ON CONFLICT DO NOTHING",
                "sid" to sessionId, "uid" to userId, "requestedAt" to matchRequestedAt).fetch().rowsUpdated().awaitSingle()
        }
    }
    suspend fun pendingMatches(userId: Long): List<ProgressionMatch> =
        query("""SELECT s.id,g.win_user_id,g.loss_user_id,s.status,s.end_reason,
            CASE WHEN s.game_type='PVP' THEN (s.ended_at AT TIME ZONE 'Asia/Seoul')::date END AS ranked_end_date
            FROM statistic_game_sessions s
            JOIN progression_matches p ON p.session_id=s.session_id AND p.user_id=:uid
            JOIN player_progression u ON u.user_id=p.user_id AND s.started_at>=u.created_at
            LEFT JOIN statistic_games g ON g.id=s.statistic_game_id
            LEFT JOIN progression_match_rewards r ON r.session_record_id=s.id AND r.user_id=:uid
            WHERE (s.left_user_id=:uid OR s.right_user_id=:uid) AND s.game_type IN ('PVP','Practice')
              AND s.started_at>=p.created_at-interval '10 seconds'
              AND s.status IN ('COMPLETED','DRAW','ABANDONED') AND r.user_id IS NULL
              AND (g.id IS NOT NULL OR s.status='ABANDONED') ORDER BY s.ended_at,s.id""", "uid" to userId)
            .map { r, _ -> ProgressionMatch(r.long("id"), r.get("win_user_id", Number::class.java)?.toLong(),
                r.get("loss_user_id", Number::class.java)?.toLong(), r.get("status", String::class.java)!!,
                r.get("end_reason", String::class.java), r.get("ranked_end_date", LocalDate::class.java)) }.all().asFlow().toList()

    suspend fun hasRankedWinXp(userId: Long, date: LocalDate): Boolean =
        query("""SELECT EXISTS (
            SELECT 1 FROM progression_match_rewards r
            JOIN statistic_game_sessions s ON s.id=r.session_record_id
            WHERE r.user_id=:uid AND r.xp>=:winXp AND s.game_type='PVP'
              AND (s.ended_at AT TIME ZONE 'Asia/Seoul')::date=:day
            )""", "uid" to userId, "winXp" to ProgressionPolicy.WIN_XP, "day" to date)
            .map { r, _ -> r.get(0, Boolean::class.javaObjectType) == true }.one().awaitSingle()

    suspend fun matchXp(userId: Long, sessionId: String): ProgressionMatchXp? =
        query("""SELECT r.xp FROM progression_match_rewards r
            JOIN statistic_game_sessions s ON s.id=r.session_record_id
            WHERE r.user_id=:uid AND s.session_id=:sid ORDER BY s.id DESC LIMIT 1""",
            "uid" to userId, "sid" to sessionId)
            .map { r, _ ->
                val total = r.long("xp").toInt()
                // 기본 경기 XP의 최댓값은 승리 XP다. 초과분은 첫 승 보너스다.
                val bonus = (total - ProgressionPolicy.WIN_XP).coerceAtLeast(0)
                ProgressionMatchXp(total - bonus, bonus)
            }.one().awaitSingleOrNull()

    suspend fun recordXp(userId: Long, sessionId: Long, xp: Int): Boolean =
        query("""INSERT INTO progression_match_rewards(user_id,session_record_id,xp) VALUES (:uid,:sid,:xp)
            ON CONFLICT DO NOTHING""", "uid" to userId, "sid" to sessionId, "xp" to xp)
            .fetch().rowsUpdated().awaitSingle() == 1L
}
