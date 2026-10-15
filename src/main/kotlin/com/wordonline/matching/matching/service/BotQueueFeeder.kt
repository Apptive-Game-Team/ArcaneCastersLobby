package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.config.BotQueueProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.repository.BotQueueRepository
import com.wordonline.matching.matching.repository.MatchTicketRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Puts bot tickets into the matching queue so the lobby's matching loop creates bot-vs-bot
 * games, instead of the game server creating them itself.
 *
 * Wanted bot games are the sum over `ACTIVE` game server rows of
 * `COALESCE(target_bot_sessions, matching.bot-queue.default-target-per-server)`. Each run tops
 * the bot tickets up until `QUEUED + ALLOCATING + MATCHED` bot tickets reach two per wanted game,
 * at most `max-enqueue-per-tick` at a time.
 *
 * A bot ticket skips everything a human ticket goes through in
 * [GameMatchService.createTicket]: there is no deck to validate (the game server loads the
 * bot's selected deck for a `Practice` session) and no user status to set (bots have none).
 * Ticket cleanup is shared with humans: a finished session retires both bot tickets through
 * [LostSessionRecovery], which frees the bots for the next run.
 */
@Service
class BotQueueFeeder(
    private val properties: BotQueueProperties,
    private val botQueueRepository: BotQueueRepository,
    private val botTicketCensus: BotTicketCensus,
    private val matchTicketRepository: MatchTicketRepository,
) {
    private val clock = Clock.systemUTC()
    private val log = LoggerFactory.getLogger(javaClass)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error("Unexpected error while feeding bot tickets", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    /** Last reason a run enqueued nothing, so a steady idle state is logged at info once. */
    @Volatile
    private var lastIdleReason: String? = null

    /** `@Scheduled` cannot invoke a suspend function, so the annotated method only launches. */
    @Scheduled(fixedDelayString = "\${matching.bot-queue.interval}")
    fun scheduledFeed() {
        if (!properties.enabled) return
        scope.launch { feed() }
    }

    /** Returns the bot tickets this run enqueued. */
    suspend fun feed(): List<MatchTicket> {
        val wanted = botQueueRepository.findActiveGameServerTargets(properties.defaultTargetPerServer)
            .sumOf { it.targetBotSessions.coerceAtLeast(0) }
        if (wanted == 0) return idle("no ACTIVE game server wants bot games")

        val live = botTicketCensus.liveBotTickets()
        val queued = live.count { it.state == MatchTicketState.QUEUED }
        val inProgress = live.size - queued
        val count = botsToEnqueue(wanted, queued, inProgress, properties.maxEnqueuePerTick)
        if (count == 0) {
            lastIdleReason = null
            return emptyList()
        }

        val matchable = botQueueRepository.findMatchableBots()
        if (matchable.size < 2) return idle("fewer than two matchable bot personas")

        val busy = live.mapTo(HashSet()) { it.userId }
        val enqueued = matchable
            .filter { it.userId !in busy }
            .take(count)
            .map { bot -> matchTicketRepository.enqueue(newTicket(bot.userId, bot.mmr)) }
        lastIdleReason = null
        if (enqueued.isNotEmpty()) {
            log.info(
                "Bot tickets enqueued: wanted={}, queued={}, inProgress={}, botIds={}",
                wanted,
                queued,
                inProgress,
                enqueued.map { it.userId },
            )
        }
        return enqueued
    }

    private fun newTicket(botId: Long, mmr: Long): MatchTicket {
        val now = Instant.now(clock)
        return MatchTicket(
            ticketId = UUID.randomUUID().toString(),
            userId = botId,
            mmr = mmr,
            state = MatchTicketState.QUEUED,
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun idle(reason: String): List<MatchTicket> {
        if (lastIdleReason != reason) {
            log.info("Bot queue feeder idle: {}", reason)
            lastIdleReason = reason
        } else {
            log.debug("Bot queue feeder idle: {}", reason)
        }
        return emptyList()
    }

    companion object {
        /**
         * Bot tickets to add: two per wanted game, minus the bot tickets already queued or in a
         * game ([inProgressTickets] counts tickets, two per game), at most [maxPerTick].
         */
        fun botsToEnqueue(wantedGames: Int, queuedTickets: Int, inProgressTickets: Int, maxPerTick: Int): Int =
            (2 * wantedGames - queuedTickets - inProgressTickets).coerceIn(0, maxPerTick.coerceAtLeast(0))
    }
}
