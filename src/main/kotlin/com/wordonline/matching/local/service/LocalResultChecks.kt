package com.wordonline.matching.local.service

import com.wordonline.matching.local.domain.LocalGameOutcome
import com.wordonline.matching.local.domain.LocalResultRejection
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.repository.StatisticCount
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** A result body that passed the shape checks; only the plausibility checks are left. */
data class ValidLocalResult(
    val outcome: LocalGameOutcome,
    val winUserId: Long?,
    val lossUserId: Long?,
    val durationMillis: Long,
    val frameCount: Long,
    val tickRate: Int,
    val localServerVersion: String,
    val decks: List<StatisticCount>,
    val magics: List<StatisticCount>,
)

/**
 * The checks of a reported local result that need nothing but the session and the body, in the
 * order of the contract table. `WRONG_USER` and `DAILY_LIMIT` need the caller and a counter, so the
 * service runs them itself.
 */
object LocalResultChecks {

    /** Largest allowed gap between the frame time and the reported time, as a share of the latter. */
    const val FRAME_DURATION_TOLERANCE = 0.10

    fun firstFailure(
        session: LocalSessionRecord,
        result: ValidLocalResult,
        now: Instant,
        minimumWinMillis: Long,
    ): LocalResultRejection? = when {
        Duration.between(session.issuedAt, now).toMillis() < result.durationMillis ->
            LocalResultRejection.DURATION_EXCEEDS_WALL_CLOCK
        framesDisagree(result) -> LocalResultRejection.FRAME_DURATION_MISMATCH
        result.outcome == LocalGameOutcome.WIN && result.durationMillis < minimumWinMillis ->
            LocalResultRejection.TOO_SHORT
        !participantsMatch(session, result) -> LocalResultRejection.PARTICIPANT_MISMATCH
        else -> null
    }

    private fun framesDisagree(result: ValidLocalResult): Boolean {
        if (result.tickRate <= 0) return true
        val frameSeconds = result.frameCount.toDouble() / result.tickRate
        val reportedSeconds = result.durationMillis / 1000.0
        return abs(frameSeconds - reportedSeconds) > reportedSeconds * FRAME_DURATION_TOLERANCE
    }

    // Beyond the winner and loser, every deck and magic row must belong to one of the two
    // participants too, or a result could write statistics under any user id.
    private fun participantsMatch(session: LocalSessionRecord, result: ValidLocalResult): Boolean {
        val participants = setOf(session.userId, session.rightUserId)
        val sidesMatch = if (result.outcome == LocalGameOutcome.WIN) {
            result.winUserId != null && result.lossUserId != null &&
                result.winUserId != result.lossUserId &&
                result.winUserId in participants && result.lossUserId in participants
        } else {
            result.winUserId == null && result.lossUserId == null
        }
        return sidesMatch && (result.decks + result.magics).all { it.userId in participants }
    }
}
