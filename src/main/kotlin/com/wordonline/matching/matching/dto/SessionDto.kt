package com.wordonline.matching.matching.dto

data class SessionDto(
    val sessionId: String,
    val uid1: Long,
    val uid2: Long?,
    val sessionType: SessionType,
    val scenarioId: Long?,
    val leftDeckCardIds: List<Long>? = null,
    val rightDeckCardIds: List<Long>? = null,
) {
    companion object {
        fun PVP(
            sessionId: String,
            uid1: Long,
            uid2: Long,
            leftDeckCardIds: List<Long>? = null,
            rightDeckCardIds: List<Long>? = null,
        ) = SessionDto(sessionId, uid1, uid2, SessionType.PVP, null, leftDeckCardIds, rightDeckCardIds)

        fun PVE(sessionId: String, userId: Long, scenarioId: Long) =
            SessionDto(sessionId, userId, null, SessionType.PVE, scenarioId)

        fun Practice(sessionId: String, uid1: Long, uid2: Long) =
            SessionDto(sessionId, uid1, uid2, SessionType.Practice, null)

        fun from(
            sessionId: String,
            uid1: Long,
            uid2: Long,
            leftDeckCardIds: List<Long>? = null,
            rightDeckCardIds: List<Long>? = null,
        ) = if (uid2 < 0) Practice(sessionId, uid1, uid2)
        else PVP(sessionId, uid1, uid2, leftDeckCardIds, rightDeckCardIds)
    }
}
