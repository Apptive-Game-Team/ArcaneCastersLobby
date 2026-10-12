package com.wordonline.matching.local.domain

import java.time.Instant

/** What a local session plays. [gameType] is the `game_type` enum value it is recorded under. */
enum class LocalSessionType(val gameType: String) {
    PRACTICE("Practice"),
    PVE("PVE"),
}

enum class LocalSessionState { ISSUED, REPORTED }

/**
 * An issued local session, kept in Redis until it is reported or expires.
 *
 * @property rightUserId the bot for `PRACTICE`, `-1` for `PVE`
 * @property hospitalityBot whether the bot is the tutorial opponent, which decides whether a win
 *   moves the player's novice progress
 */
data class LocalSessionRecord(
    val sessionId: String,
    val userId: Long,
    val type: LocalSessionType,
    val scenarioId: Long?,
    val rightUserId: Long,
    val hospitalityBot: Boolean,
    val issuedAt: Instant,
    val state: LocalSessionState,
)

enum class LocalGameOutcome { WIN, DRAW, ABANDONED }

/** Why a reported local result was not recorded. The names are sent to the client as is. */
enum class LocalResultRejection {
    WRONG_USER,
    DURATION_EXCEEDS_WALL_CLOCK,
    FRAME_DURATION_MISMATCH,
    TOO_SHORT,
    PARTICIPANT_MISMATCH,
    DAILY_LIMIT,
}
