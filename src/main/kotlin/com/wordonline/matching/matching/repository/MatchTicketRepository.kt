package com.wordonline.matching.matching.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.domain.Range
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant

@Repository
class MatchTicketRepository(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: MatchTicketProperties,
) {
    private val clock = Clock.systemUTC()
    companion object {
        private const val QUEUE_KEY = "matching:queue"
        private const val ACTIVE_PREFIX = "matching:active:"
        private const val TICKET_PREFIX = "matching:ticket:"
        private const val EVENT_PREFIX = "matching:events:"
        private const val LEASE_KEY = "matching:allocation-leases"

        /**
         * Index of tickets currently in `MATCHED`, scored by the instant they got there.
         * Without it there is no way to iterate live sessions, so a game server that
         * restarted left both tickets stuck with nobody watching them.
         */
        const val MATCHED_KEY = "matching:matched"

        /**
         * Tickets a client reported as lost that the lobby could not verify yet, scored by
         * the time of the report.
         *
         * A deferred verdict leaves the ticket `MATCHED`, and the client re-enters the game
         * scene on any `MATCHED` snapshot, so it bounces between lobby and game until the
         * verdict lands. Waiting a full sweep for that is the difference between a couple of
         * bounces and a couple of dozen; the reconciler drains this set on a much shorter
         * schedule. It changes only how soon a session is re-probed, never what counts as
         * proof that it ended.
         */
        const val PENDING_VERIFICATION_KEY = "matching:matched:pending"
    }

    private val enqueueScript = DefaultRedisScript(ENQUEUE_SCRIPT, String::class.java)
    private val claimScript = DefaultRedisScript(CLAIM_SCRIPT, Long::class.java)
    private val claimSingleScript = DefaultRedisScript(CLAIM_SINGLE_SCRIPT, Long::class.java)
    private val transitionScript = DefaultRedisScript(TRANSITION_SCRIPT, String::class.java)
    private val transitionPairScript = DefaultRedisScript(TRANSITION_PAIR_SCRIPT, Long::class.java)
    private val cancelScript = DefaultRedisScript(CANCEL_SCRIPT, String::class.java)

    suspend fun enqueue(ticket: MatchTicket): MatchTicket {
        val json = encode(ticket)
        val result = redis.execute(
            enqueueScript,
            listOf(QUEUE_KEY, activeKey(ticket.userId), ticketKey(ticket.ticketId), eventKey(ticket.userId)),
            listOf(json, ticket.userId.toString(), ticket.updatedAt.toEpochMilli().toString()),
        ).next().awaitSingle()
        return decode(result)
    }

    suspend fun getActive(userId: Long): MatchTicket? {
        val ticketId = redis.opsForValue().get(activeKey(userId)).awaitSingleOrNull() ?: return null
        return redis.opsForValue().get(ticketKey(ticketId)).awaitSingleOrNull()?.let(::decode)
    }

    suspend fun claim(first: MatchTicket, second: MatchTicket): Boolean {
        val result = redis.execute(
            claimScript,
            listOf(QUEUE_KEY, ticketKey(first.ticketId), ticketKey(second.ticketId), eventKey(first.userId), eventKey(second.userId), LEASE_KEY),
            listOf(encode(first), encode(second), first.userId.toString(), second.userId.toString(), first.allocationLeaseUntil!!.toEpochMilli().toString()),
        ).next().awaitSingle()
        return result == 1L
    }

    /**
     * Moves one `QUEUED` ticket to `ALLOCATING` and puts it under an allocation lease, so a
     * lobby that dies mid-allocation still gets the ticket recovered. [ticket] is the
     * already-updated `ALLOCATING` copy.
     */
    suspend fun claim(ticket: MatchTicket): Boolean {
        val result = redis.execute(
            claimSingleScript,
            listOf(QUEUE_KEY, ticketKey(ticket.ticketId), eventKey(ticket.userId), LEASE_KEY),
            listOf(encode(ticket), ticket.userId.toString(), ticket.allocationLeaseUntil!!.toEpochMilli().toString()),
        ).next().awaitSingle()
        return result == 1L
    }

    suspend fun transition(ticket: MatchTicket, expected: MatchTicketState): MatchTicket? {
        val result = redis.execute(
            transitionScript,
            listOf(ticketKey(ticket.ticketId), eventKey(ticket.userId), LEASE_KEY, QUEUE_KEY, activeKey(ticket.userId), MATCHED_KEY),
            listOf(
                expected.name,
                encode(ticket),
                ticket.ticketId,
                properties.terminalTtl.toMillis().toString(),
                ticket.state.name,
                ticket.userId.toString(),
                ticket.updatedAt.toEpochMilli().toString(),
                ticket.state.isTerminal.toString(),
            ),
        ).next().awaitSingleOrNull()
        return result?.takeIf { it.isNotEmpty() }?.let(::decode)
    }

    suspend fun transitionPair(first: MatchTicket, second: MatchTicket, expected: MatchTicketState): Boolean {
        val result = redis.execute(
            transitionPairScript,
            listOf(
                ticketKey(first.ticketId),
                ticketKey(second.ticketId),
                eventKey(first.userId),
                eventKey(second.userId),
                LEASE_KEY,
                activeKey(first.userId),
                activeKey(second.userId),
                MATCHED_KEY,
                QUEUE_KEY,
            ),
            listOf(
                expected.name,
                encode(first),
                encode(second),
                first.ticketId,
                second.ticketId,
                properties.terminalTtl.toMillis().toString(),
                first.state.name,
                first.userId.toString(),
                second.userId.toString(),
                first.updatedAt.toEpochMilli().toString(),
                first.state.isTerminal.toString(),
            ),
        ).next().awaitSingle()
        return result == 1L
    }

    suspend fun cancel(userId: Long, ticketId: String): MatchTicket? {
        val result = redis.execute(
            cancelScript,
            listOf(activeKey(userId), QUEUE_KEY, eventKey(userId), LEASE_KEY),
            listOf(TICKET_PREFIX, userId.toString(), Instant.now(clock).toString(), properties.terminalTtl.toMillis().toString(), ticketId),
        ).next().awaitSingleOrNull()
        return result?.takeIf { it.isNotEmpty() }?.let(::decode)
    }

    suspend fun findCandidates(limit: Long = 50): List<MatchTicket> =
        redis.opsForZSet().range(QUEUE_KEY, Range.closed(0L, limit - 1)).collectList().awaitSingle()
            .mapNotNull { getActive(it.toLong()) }
            .filter { it.state == MatchTicketState.QUEUED }

    suspend fun size(): Long = redis.opsForZSet().size(QUEUE_KEY).awaitSingle()

    suspend fun expiredAllocations(): List<MatchTicket> {
        val now = Instant.now(clock).toEpochMilli().toDouble()
        val ids = redis.opsForZSet().rangeByScore(LEASE_KEY, Range.closed(Double.NEGATIVE_INFINITY, now)).collectList().awaitSingle()
        return ids.mapNotNull { id -> redis.opsForValue().get(ticketKey(id)).awaitSingleOrNull()?.let(::decode) }
    }

    suspend fun expiredQueued(): List<MatchTicket> {
        val cutoff = Instant.now(clock).minus(properties.queueTimeout).toEpochMilli().toDouble()
        val ids = redis.opsForZSet().rangeByScore(QUEUE_KEY, Range.closed(Double.NEGATIVE_INFINITY, cutoff)).collectList().awaitSingle()
        return ids.mapNotNull { getActive(it.toLong()) }.filter { it.state == MatchTicketState.QUEUED }
    }

    /**
     * Oldest `MATCHED` tickets first, skipping anything that reached `MATCHED` more recently
     * than [settledBefore].
     *
     * Index entries whose ticket key is gone, or whose ticket has since moved on, are pruned
     * here: nothing else would ever remove them once a ticket key expires.
     */
    suspend fun settledMatched(settledBefore: Instant, limit: Long): List<MatchTicket> =
        stillMatched(MATCHED_KEY, settledBefore.toEpochMilli().toDouble(), limit)

    /**
     * The tickets still in `MATCHED` on [sessionId], normally the two sides of one match.
     *
     * There is no session index: a ticket is reachable by user or by ticket id, and the game
     * server's end-of-session notification knows neither. Scanning the `MATCHED` index instead
     * of adding a third index keeps the redis scripts - which is where every ordering bug in
     * this repository has lived - untouched, and the index only ever holds one entry per side
     * of a running match. Read-only on purpose: pruning stale entries is the reconciler's job.
     */
    suspend fun matchedBySession(sessionId: String): List<MatchTicket> {
        val ids = redis.opsForZSet()
            .rangeByScore(MATCHED_KEY, Range.closed(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            .collectList()
            .awaitSingle()

        return ids.mapNotNull { ticketId -> redis.opsForValue().get(ticketKey(ticketId)).awaitSingleOrNull()?.let(::decode) }
            .filter { it.state == MatchTicketState.MATCHED && it.matchInfo?.sessionId == sessionId }
    }

    /** Records that a reported ticket could not be verified yet, for a fast re-check. */
    suspend fun markPendingVerification(ticketId: String, reportedAt: Instant) {
        redis.opsForZSet().add(PENDING_VERIFICATION_KEY, ticketId, reportedAt.toEpochMilli().toDouble()).awaitSingleOrNull()
    }

    suspend fun clearPendingVerification(ticketId: String) {
        redis.opsForZSet().remove(PENDING_VERIFICATION_KEY, ticketId).awaitSingleOrNull()
    }

    /** Reported-but-unverified tickets, oldest report first. No grace period: they were reported. */
    suspend fun pendingVerification(limit: Long): List<MatchTicket> =
        stillMatched(PENDING_VERIFICATION_KEY, Double.POSITIVE_INFINITY, limit)

    /**
     * Reads an index of ticket ids and drops the entries that no longer name a `MATCHED`
     * ticket. Nothing else prunes them once a ticket key expires.
     */
    private suspend fun stillMatched(indexKey: String, maxScore: Double, limit: Long): List<MatchTicket> {
        val ids = redis.opsForZSet()
            .rangeByScore(
                indexKey,
                Range.closed(Double.NEGATIVE_INFINITY, maxScore),
                org.springframework.data.redis.connection.Limit.limit().count(limit.toInt()),
            )
            .collectList()
            .awaitSingle()

        return ids.mapNotNull { ticketId ->
            val ticket = redis.opsForValue().get(ticketKey(ticketId)).awaitSingleOrNull()?.let(::decode)
            if (ticket == null || ticket.state != MatchTicketState.MATCHED) {
                redis.opsForZSet().remove(indexKey, ticketId).awaitSingleOrNull()
                null
            } else {
                ticket
            }
        }
    }

    fun events(userId: Long) = redis.listenToChannel(eventKey(userId))
        .map { decode(it.message) }
        .asFlow()

    private fun encode(ticket: MatchTicket): String = objectMapper.writeValueAsString(ticket)
    private fun decode(json: String): MatchTicket = objectMapper.readValue(json, MatchTicket::class.java)
    private fun activeKey(userId: Long) = "$ACTIVE_PREFIX$userId"
    private fun ticketKey(ticketId: String) = "$TICKET_PREFIX$ticketId"
    private fun eventKey(userId: Long) = "$EVENT_PREFIX$userId"

}

private const val ENQUEUE_SCRIPT = """
local active = redis.call('GET', KEYS[2])
if active then
  local current = redis.call('GET', 'matching:ticket:' .. active)
  if current then
    local state = cjson.decode(current).state
    if state == 'QUEUED' or state == 'ALLOCATING' or state == 'MATCHED' then return current end
  end
end
redis.call('SET', KEYS[3], ARGV[1])
redis.call('SET', KEYS[2], cjson.decode(ARGV[1]).ticketId)
redis.call('ZADD', KEYS[1], tonumber(ARGV[3]), ARGV[2])
redis.call('PUBLISH', KEYS[4], ARGV[1])
return ARGV[1]
"""

/**
 * Lua helper shared by the transition scripts. `settle(storedRaw, payload)` returns the
 * payload to write, or `nil` when the transition must be refused.
 *
 * The payload is computed in Kotlin from a copy read before the transition, so it cannot
 * know about a `cancelRequested` written in between. Only when the stored ticket carries
 * that flag is the payload decoded and rewritten, which keeps every other transition
 * byte-for-byte as Kotlin produced it:
 * - `QUEUED` is refused, so a user who canceled is not silently re-queued;
 * - `FAILED` becomes `CANCELED` with reason `USER_REQUESTED` for the canceling side;
 * - the version is raised above the stored one, since the cancel bumped it and a client
 *   ignores a ticket whose version it has already seen.
 */
private const val SETTLE_FUNCTION = """
local function settle(storedRaw, payload)
  local stored = cjson.decode(storedRaw)
  if stored.cancelRequested ~= true then return payload end
  local ticket = cjson.decode(payload)
  if ticket.state == 'QUEUED' then return nil end
  if ticket.state == 'FAILED' then ticket.state = 'CANCELED'; ticket.reason = 'USER_REQUESTED' end
  if ticket.version <= stored.version then ticket.version = stored.version + 1 end
  return cjson.encode(ticket)
end
"""

private const val CLAIM_SCRIPT = """
local first = redis.call('GET', KEYS[2]); local second = redis.call('GET', KEYS[3])
if not first or not second or cjson.decode(first).state ~= 'QUEUED' or cjson.decode(second).state ~= 'QUEUED' then return 0 end
redis.call('SET', KEYS[2], ARGV[1]); redis.call('SET', KEYS[3], ARGV[2])
redis.call('ZREM', KEYS[1], ARGV[3], ARGV[4])
redis.call('ZADD', KEYS[6], ARGV[5], cjson.decode(ARGV[1]).ticketId, ARGV[5], cjson.decode(ARGV[2]).ticketId)
redis.call('PUBLISH', KEYS[4], ARGV[1]); redis.call('PUBLISH', KEYS[5], ARGV[2])
return 1
"""

private const val CLAIM_SINGLE_SCRIPT = """
local current = redis.call('GET', KEYS[2])
if not current or cjson.decode(current).state ~= 'QUEUED' then return 0 end
redis.call('SET', KEYS[2], ARGV[1])
redis.call('ZREM', KEYS[1], ARGV[2])
redis.call('ZADD', KEYS[4], ARGV[3], cjson.decode(ARGV[1]).ticketId)
redis.call('PUBLISH', KEYS[3], ARGV[1])
return 1
"""

/**
 * KEYS: ticket, events, leases, queue, active, matched-index.
 * ARGV: expected state, payload, ticketId, terminalTtlMs, new state, userId, updatedAtMs, terminal flag.
 *
 * `SET` without `PX` clears any TTL left over from an earlier state, and the expiry is
 * re-applied only for terminal states - a `MATCHED` ticket must outlive `terminalTtl`.
 * The active pointer is released by compare-and-delete so a user who already re-queued
 * keeps the newer ticket.
 */
private const val TRANSITION_SCRIPT = """$SETTLE_FUNCTION
local current = redis.call('GET', KEYS[1])
if not current or cjson.decode(current).state ~= ARGV[1] then return '' end
local payload = settle(current, ARGV[2]); if not payload then return '' end
local settled = cjson.decode(payload).state
redis.call('SET', KEYS[1], payload); redis.call('ZREM', KEYS[3], ARGV[3])
redis.call('PUBLISH', KEYS[2], payload)
if settled == 'QUEUED' then redis.call('ZADD', KEYS[4], ARGV[7], ARGV[6]) else redis.call('ZREM', KEYS[4], ARGV[6]) end
if settled == 'MATCHED' then redis.call('ZADD', KEYS[6], ARGV[7], ARGV[3]) else redis.call('ZREM', KEYS[6], ARGV[3]) end
if settled == 'CANCELED' or settled == 'EXPIRED' or settled == 'FAILED' then
  redis.call('PEXPIRE', KEYS[1], ARGV[4])
  if redis.call('GET', KEYS[5]) == ARGV[3] then redis.call('DEL', KEYS[5]) end
end
return payload
"""

private const val CANCEL_SCRIPT = """
local id = redis.call('GET', KEYS[1]); if not id then return '' end
if id ~= ARGV[5] then return '' end
local key = ARGV[1] .. id; local raw = redis.call('GET', key); if not raw then return '' end
local ticket = cjson.decode(raw)
if ticket.state == 'QUEUED' then
 ticket.state = 'CANCELED'; ticket.reason = 'USER_REQUESTED'; ticket.version = ticket.version + 1; ticket.updatedAt = ARGV[3]
 local updated = cjson.encode(ticket); redis.call('SET', key, updated); redis.call('ZREM', KEYS[2], ARGV[2]); redis.call('ZREM', KEYS[4], id)
 redis.call('PUBLISH', KEYS[3], updated); redis.call('PEXPIRE', key, ARGV[4]); redis.call('DEL', KEYS[1]); return updated
end
if ticket.state == 'ALLOCATING' and ticket.cancelRequested ~= true then
 ticket.cancelRequested = true; ticket.version = ticket.version + 1; ticket.updatedAt = ARGV[3]
 local updated = cjson.encode(ticket); redis.call('SET', key, updated); redis.call('PUBLISH', KEYS[3], updated); return updated
end
return raw
"""

/**
 * KEYS: first ticket, second ticket, first events, second events, leases,
 *       first active, second active, matched-index, queue.
 * ARGV: expected state, first payload, second payload, first ticketId, second ticketId,
 *       terminalTtlMs, new state, first userId, second userId, updatedAtMs, terminal flag.
 *
 * The unconditional `SET ... 'PX'` this replaces silently expired `MATCHED` tickets after
 * `terminalTtl`, killing the ticket of any session that ran longer than 30 minutes.
 */
private const val TRANSITION_PAIR_SCRIPT = """$SETTLE_FUNCTION
local first = redis.call('GET', KEYS[1]); local second = redis.call('GET', KEYS[2])
if not first or not second or cjson.decode(first).state ~= ARGV[1] or cjson.decode(second).state ~= ARGV[1] then return 0 end
local firstPayload = settle(first, ARGV[2]); local secondPayload = settle(second, ARGV[3])
if not firstPayload or not secondPayload then return 0 end
redis.call('SET', KEYS[1], firstPayload); redis.call('SET', KEYS[2], secondPayload)
redis.call('ZREM', KEYS[5], ARGV[4], ARGV[5])
redis.call('ZREM', KEYS[9], ARGV[8], ARGV[9])
if ARGV[7] == 'MATCHED' then
  redis.call('ZADD', KEYS[8], ARGV[10], ARGV[4], ARGV[10], ARGV[5])
else
  redis.call('ZREM', KEYS[8], ARGV[4], ARGV[5])
end
if ARGV[11] == 'true' then
  redis.call('PEXPIRE', KEYS[1], ARGV[6]); redis.call('PEXPIRE', KEYS[2], ARGV[6])
  if redis.call('GET', KEYS[6]) == ARGV[4] then redis.call('DEL', KEYS[6]) end
  if redis.call('GET', KEYS[7]) == ARGV[5] then redis.call('DEL', KEYS[7]) end
end
redis.call('PUBLISH', KEYS[3], firstPayload); redis.call('PUBLISH', KEYS[4], secondPayload)
return 1
"""
