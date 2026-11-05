package com.wordonline.matching.progression

import com.wordonline.matching.support.MigratedPostgresDatabase
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.File

@EnabledIfEnvironmentVariable(named = "QUEST_IT_DATABASE_URL", matches = ".+")
class ManualRewardsMigrationPostgresIntegrationTest {
    @Test
    fun `V123의 자동 지급 계정은 V124 이후 기준선 이하를 회수하거나 재지급하지 않는다`(): Unit = runBlocking {
        val factory = MigratedPostgresDatabase.build("manual_rewards_upgrade_it", 123, throughVersion = 123)
        val db = DatabaseClient.create(factory)
        suspend fun exec(sql: String) { db.sql(sql).fetch().rowsUpdated().awaitSingle() }
        suspend fun number(sql: String): Long = db.sql(sql).map { r, _ -> r.get(0, Number::class.java)!!.toLong() }.one().awaitSingle()
        val user = 3000001L
        exec("INSERT INTO users(id) VALUES ($user)")
        exec("INSERT INTO player_progression(user_id,faction,xp,level) VALUES ($user,'HELL',900,10)")
        exec("INSERT INTO user_magics(user_id,magic_id,count) SELECT $user,id,5 FROM magics WHERE name='fire_shot'")
        exec("INSERT INTO user_chests(user_id,chest_id,reward_source,reward_slots) SELECT $user,id,'level:10',6 FROM chests WHERE key='progression_chest'")

        val migrationDirectory = File(System.getenv(MigratedPostgresDatabase.MIGRATION_DIRECTORY_VARIABLE) ?: "../database/migration")
        val migration = migrationDirectory.listFiles()!!.single { it.name.startsWith("V124_") }
        val connection = Mono.from(factory.create()).awaitSingle()
        try {
            Flux.from(connection.createStatement("BEGIN;\n${migration.readText()}\nCOMMIT;").execute())
                .concatMap { Flux.from(it.rowsUpdated) }.then().awaitSingleOrNull()
        } finally {
            Mono.from(connection.close()).awaitSingleOrNull()
        }
        val repository = ProgressionRepository(db)
        val service = ProgressionService(repository, ProgressionProperties(), TransactionalOperator.create(R2dbcTransactionManager(factory)))
        val pending = service.pendingRewards(user, true)
        assertThat(pending.baselineLevel).isEqualTo(10)
        assertThat(pending.claimedLevels).contains(10)
        assertThat(pending.rewards.none { it.level != null && it.level <= 10 }).isTrue()
        assertThat(number("SELECT count FROM user_magics WHERE user_id=$user")).isEqualTo(5)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)
        exec("UPDATE player_progression SET xp=1150 WHERE user_id=$user")
        assertThat(service.pendingRewards(user, true).rewards.filter { it.level != null }.map { it.level }).containsExactly(11)
        assertThat(number("SELECT COUNT(*) FROM user_chests WHERE user_id=$user")).isEqualTo(1)

        var invalidSource: Exception? = null
        try { exec("INSERT INTO progression_reward_claims(user_id,source) VALUES ($user,'level:2')") }
        catch (e: Exception) { invalidSource = e }
        assertThat(invalidSource).isNotNull()
        exec("DELETE FROM users WHERE id=$user")
        assertThat(number("SELECT COUNT(*) FROM progression_reward_items")).isZero()
        assertThat(number("SELECT COUNT(*) FROM progression_reward_claims")).isZero()
    }
}
