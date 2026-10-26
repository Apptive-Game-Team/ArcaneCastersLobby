package com.wordonline.matching.local.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.domain.LocalSessionState
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDate

/** Result of [LocalSessionStore.transition]. */
enum class LocalSessionTransition { MISSING, STATE_MISMATCH, TRANSITIONED }

/**
 * Issued local sessions and the per-day count of accepted local results, in Redis.
 *
 * A session is one JSON string at `local-session:{sessionId}` with the TTL of
 * [LocalSessionProperties.sessionTtl]. Its state changes only through [transition], a script that
 * compares and sets in one step, so two reports of the same session cannot both pass.
 */
@Repository
class LocalSessionStore(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: LocalSessionProperties,
) {
    private val transitionScript = DefaultRedisScript(TRANSITION_SCRIPT, Long::class.java)

    suspend fun save(record: LocalSessionRecord) {
        redis.opsForValue()
            .set(sessionKey(record.sessionId), objectMapper.writeValueAsString(record), properties.sessionTtl)
            .awaitSingle()
    }

    suspend fun find(sessionId: String): LocalSessionRecord? =
        redis.opsForValue().get(sessionKey(sessionId)).awaitSingleOrNull()
            ?.let { objectMapper.readValue(it, LocalSessionRecord::class.java) }

    /** Moves the session from [from] to [to] and keeps its remaining TTL. */
    suspend fun transition(sessionId: String, from: LocalSessionState, to: LocalSessionState): LocalSessionTransition {
        val result = redis.execute(transitionScript, listOf(sessionKey(sessionId)), listOf(from.name, to.name))
            .next()
            .awaitSingle()
        return when (result) {
            0L -> LocalSessionTransition.MISSING
            1L -> LocalSessionTransition.STATE_MISMATCH
            else -> LocalSessionTransition.TRANSITIONED
        }
    }

    /** Counts one more accepted result for [userId] on [day] and returns the new count. */
    suspend fun incrementDailyAccepted(userId: Long, day: LocalDate): Long {
        val key = dailyKey(userId, day)
        val count = redis.opsForValue().increment(key).awaitSingle()
        if (count == 1L) {
            // Outlives the day it counts by a margin; the key of the next day is a different key.
            redis.expire(key, DAILY_COUNTER_TTL).awaitSingle()
        }
        return count
    }

    suspend fun decrementDailyAccepted(userId: Long, day: LocalDate) {
        redis.opsForValue().decrement(dailyKey(userId, day)).awaitSingle()
    }

    companion object {
        private const val SESSION_PREFIX = "local-session:"
        private const val DAILY_PREFIX = "local-session-results:"
        private val DAILY_COUNTER_TTL: Duration = Duration.ofDays(2)

        fun sessionKey(sessionId: String) = "$SESSION_PREFIX$sessionId"

        fun dailyKey(userId: Long, day: LocalDate) = "$DAILY_PREFIX$userId:$day"

        /**
         * KEYS[1] session key, ARGV[1] expected state, ARGV[2] new state.
         * Returns 0 when the key is gone, 1 when the state is not ARGV[1], 2 when it moved.
         *
         * The state is replaced as text instead of decoding and re-encoding the JSON, so the other
         * fields come back byte for byte.
         */
        val TRANSITION_SCRIPT = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local expected = '"state":"' .. ARGV[1] .. '"'
            local first, last = string.find(raw, expected, 1, true)
            if not first then return 1 end
            local updated = string.sub(raw, 1, first - 1) .. '"state":"' .. ARGV[2] .. '"' .. string.sub(raw, last + 1)
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl > 0 then
                redis.call('SET', KEYS[1], updated, 'PX', ttl)
            else
                redis.call('SET', KEYS[1], updated)
            end
            return 2
        """.trimIndent()
    }
}
