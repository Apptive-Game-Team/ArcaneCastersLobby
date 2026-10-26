package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.service.UserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** The bot a practice match is played against, and whether it is the hospitality bot. */
data class PracticeOpponent(
    val botId: Long,
    val hospitality: Boolean,
)

/**
 * Picks the bot for a practice match. Shared by the shared-server practice match and the local
 * session, so both apply the same tutorial rule.
 */
@Service
class PracticeOpponentSelector(
    private val botMemberMaker: BotMemberMaker,
    private val userService: UserService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // A player who still carries the novice mark is finishing the tutorial, and meets the opponent
    // built to lose. The fallback matters: the tutorial opponent being unavailable must not be the
    // reason a new player cannot start a match at all, so they get an ordinary bot and keep the
    // mark for next time. Everyone else meets a bot near their own MMR.
    suspend fun select(userId: Long): PracticeOpponent {
        if (!userService.isNovice(userId).awaitSingle()) {
            return PracticeOpponent(botNearMmrOf(userId), hospitality = false)
        }

        return try {
            PracticeOpponent(botMemberMaker.getHospitalityBotId().awaitSingle(), hospitality = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No hospitality bot available for novice userId={}; falling back to the bot pool", userId, e)
            PracticeOpponent(botNearMmrOf(userId), hospitality = false)
        }
    }

    private suspend fun botNearMmrOf(userId: Long): Long =
        botMemberMaker.getPracticeBotIdNear(userService.getMmr(userId).awaitSingle())
}
