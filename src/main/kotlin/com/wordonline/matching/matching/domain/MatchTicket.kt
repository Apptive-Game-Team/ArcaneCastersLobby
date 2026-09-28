package com.wordonline.matching.matching.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.wordonline.matching.matching.dto.MatchedInfoDto
import java.time.Instant

enum class MatchTicketState {
    QUEUED,
    ALLOCATING,
    MATCHED,
    CANCELED,
    EXPIRED,
    FAILED,
    ;

    /**
     * A terminal ticket is finished for good: it is the only kind that may expire and the
     * only kind that releases `matching:active:<userId>`. `MATCHED` is deliberately not
     * terminal - a session outliving the terminal TTL must keep its ticket.
     */
    val isTerminal: Boolean
        get() = this == CANCELED || this == EXPIRED || this == FAILED
}

data class MatchTicket(
    val ticketId: String,
    val userId: Long,
    val mmr: Long,
    val state: MatchTicketState,
    val version: Long,
    val reason: String? = null,
    val matchInfo: MatchedInfoDto? = null,
    val attemptId: String? = null,
    val allocationLeaseUntil: Instant? = null,
    /** `servers.id` of the game server that accepted this session, for health lookups. */
    val serverId: Long? = null,
    /**
     * Boot generation of the game server process that accepted this session.
     *
     * A value differing from the current `servers.instance_id` proves the hosting process
     * restarted and took the in-memory session with it. `null` on either side is "not
     * reported", never "restarted".
     */
    val serverInstanceId: String? = null,
    /** The immutable deck snapshot sent to the game server when this ticket is paired. */
    val deckCardIds: List<Long>? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * The other side of a PVP session, or `null` for bot/PVE sessions and unmatched tickets.
     *
     * `@JsonIgnore` because it is derived: without it the value would be written into the
     * redis payload and the client-facing ticket JSON as if it were stored state.
     */
    @get:JsonIgnore
    val opponentUserId: Long?
        get() {
            val info = matchInfo ?: return null
            val opponent = if (info.leftUser.id() == userId) info.rightUser.id() else info.leftUser.id()
            return opponent.takeIf { it > 0 && it != userId }
        }
}

enum class CancelMatchResult {
    CANCELED,
    TOO_LATE,
    ALREADY_FINISHED,
    NOT_FOUND,
}

data class CancelMatchResponse(
    val result: CancelMatchResult,
    val ticket: MatchTicket?,
)
