package com.wordonline.matching.progression

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

data class ProgressionState(val faction: String?, val xp: Long, val level: Int, val currency: Long)
data class ProgressionMagic(val id: Long, val name: String)
data class ProgressionMatch(val id: Long, val winner: Long?, val loser: Long?, val status: String, val reason: String?)

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
            .map { row, _ -> ProgressionState(row.get("faction", String::class.java), row.long("xp"), row.long("level").toInt(), row.long("currency")) }
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
    suspend fun fixedMagics(faction: String, level: Int): List<ProgressionMagic> =
        query("""SELECT m.id,m.name FROM progression_magic_rewards r JOIN magics m ON m.id=r.magic_id
            WHERE r.faction=:faction AND r.unlock_level=:level ORDER BY m.id""", "faction" to faction, "level" to level)
            .map { r, _ -> ProgressionMagic(r.long("id"), r.get("name", String::class.java)!!) }.all().asFlow().toList()

    suspend fun eligibleMagics(userId: Long, level: Int): List<ProgressionMagic> =
        query("""SELECT m.id,m.name FROM magics m
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

    suspend fun registerMatch(sessionId: String, userId: Long) {
        if (userId <= 0) return
        query("INSERT INTO progression_matches(session_id,user_id) VALUES (:sid,:uid) ON CONFLICT DO NOTHING",
            "sid" to sessionId, "uid" to userId).fetch().rowsUpdated().awaitSingle()
    }
    suspend fun pendingMatches(userId: Long): List<ProgressionMatch> =
        query("""SELECT s.id,g.win_user_id,g.loss_user_id,s.status,s.end_reason
            FROM statistic_game_sessions s
            JOIN progression_matches p ON p.session_id=s.session_id AND p.user_id=:uid
            JOIN player_progression u ON u.user_id=p.user_id AND s.started_at>=u.created_at
            LEFT JOIN statistic_games g ON g.id=s.statistic_game_id
            LEFT JOIN progression_match_rewards r ON r.session_record_id=s.id AND r.user_id=:uid
            WHERE (s.left_user_id=:uid OR s.right_user_id=:uid) AND s.game_type='PVP'
              AND s.started_at>=p.created_at-interval '10 seconds'
              AND s.status IN ('COMPLETED','DRAW','ABANDONED') AND r.user_id IS NULL
              AND (g.id IS NOT NULL OR s.status='ABANDONED') ORDER BY s.id""", "uid" to userId)
            .map { r, _ -> ProgressionMatch(r.long("id"), r.get("win_user_id", Number::class.java)?.toLong(),
                r.get("loss_user_id", Number::class.java)?.toLong(), r.get("status", String::class.java)!!,
                r.get("end_reason", String::class.java)) }.all().asFlow().toList()

    suspend fun recordXp(userId: Long, sessionId: Long, xp: Int): Boolean =
        query("""INSERT INTO progression_match_rewards(user_id,session_record_id,xp) VALUES (:uid,:sid,:xp)
            ON CONFLICT DO NOTHING""", "uid" to userId, "sid" to sessionId, "xp" to xp)
            .fetch().rowsUpdated().awaitSingle() == 1L
}
