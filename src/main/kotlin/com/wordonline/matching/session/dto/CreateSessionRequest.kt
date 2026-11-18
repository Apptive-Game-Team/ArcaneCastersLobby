package com.wordonline.matching.session.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.dto.SessionType

data class CreateSessionRequest(
    val attemptId: String,
    val sessionId: String,
    val uid1: Long,
    val uid2: Long?,
    val sessionType: SessionType,
    val scenarioId: Long?,
    val leftDeckCardIds: List<Long>?,
    val rightDeckCardIds: List<Long>?,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val startProtocolVersion: Int = 0,
) {
    constructor(attemptId: String, session: SessionDto) : this(
        attemptId,
        session.sessionId,
        session.uid1,
        session.uid2,
        session.sessionType,
        session.scenarioId,
        session.leftDeckCardIds,
        session.rightDeckCardIds,
        session.startProtocolVersion,
    )
}

data class SessionReadyResponse(
    val attemptId: String,
    val sessionId: String,
    val ready: Boolean,
    val serverUrl: String,
    val webSocketUrl: String,
    /**
     * Boot generation of the game server process that now owns this session. `null` when the
     * game server predates the field; the lobby then has no restart evidence and must decide
     * liveness from the session query alone.
     */
    val instanceId: String? = null,
    /**
     * Map kind the game server chose for this session (for example `GRASSLAND`). A plain string,
     * not an enum, so a kind added later passes through. `null` when the game server predates it.
     */
    val mapType: String? = null,
    val startProtocolVersion: Int = 0,
)
