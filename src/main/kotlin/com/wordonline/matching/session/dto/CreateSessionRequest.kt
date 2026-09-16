package com.wordonline.matching.session.dto

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
)
