package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.MatchDeckMode
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.dto.SimpleMessageDto
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.CancelMatchResponse
import com.wordonline.matching.matching.domain.CancelMatchResult
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.matching.repository.MatchTicketRepository
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class GameMatchService(
    private val botMemberMaker: BotMemberMaker,
    private val legacyGameMatchService: LegacyGameMatchService,
    private val userService: UserService,
    private val deckService: DeckService,
    private val randomDeckService: RandomDeckService,
    private val matchingQueueRepository: MatchingQueueRepository,
    private val matchTicketRepository: MatchTicketRepository,
    private val matchTicketProperties: MatchTicketProperties,
) {
    private val clock = Clock.systemUTC()
    private val log = LoggerFactory.getLogger(javaClass)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error("Unexpected error while trying to match users", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.Default + exceptionHandler)

    suspend fun matchPractice(userId: Long): MatchedInfoDto {
        val sessionId = "bot-${matchingQueueRepository.nextSessionId().awaitSingle()}"
        val botId = practiceOpponentFor(userId)
        val sessionDto = SessionDto.Practice(sessionId, userId, botId)
        return legacyGameMatchService.createSession(sessionDto).matchInfo
    }

    // A player who still carries the novice mark is finishing the tutorial, and meets the opponent
    // built to lose. The fallback matters: the tutorial opponent being unavailable must not be the
    // reason a new player cannot start a match at all, so they get an ordinary bot and keep the
    // mark for next time.
    private suspend fun practiceOpponentFor(userId: Long): Long {
        if (!userService.isNovice(userId).awaitSingle()) {
            return botMemberMaker.getRandomEnabledBotId().awaitSingle()
        }

        return try {
            botMemberMaker.getHospitalityBotId().awaitSingle()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No hospitality bot available for novice userId={}; falling back to the bot pool", userId, e)
            botMemberMaker.getRandomEnabledBotId().awaitSingle()
        }
    }

    suspend fun matchBots(leftBotId: Long, rightBotId: Long): MatchedInfoDto {
        val sessionId = "admin-bot-${matchingQueueRepository.nextSessionId().awaitSingle()}"
        val sessionDto = SessionDto.Practice(sessionId, leftBotId, rightBotId)
        return legacyGameMatchService.createSession(sessionDto).matchInfo
    }

    suspend fun matchPVE(userId: Long, scenarioId: Long): MatchedInfoDto {
        val sessionId = "pve-${matchingQueueRepository.nextSessionId().awaitSingle()}"
        val sessionDto = SessionDto.PVE(sessionId, userId, scenarioId)
        return legacyGameMatchService.createSession(sessionDto).matchInfo
    }

    suspend fun match(userId: Long): SimpleMessageDto {
        if (enqueue(userId, MatchDeckMode.SELECTED) == null) {
            return SimpleMessageDto("Failed to enqueue user")
        }

        return SimpleMessageDto("Successfully Enqueued")
    }

    suspend fun createTicket(userId: Long, deckMode: MatchDeckMode): MatchTicket =
        enqueue(userId, deckMode) ?: throw IllegalStateException("Failed to enqueue user")

    private suspend fun enqueue(userId: Long, deckMode: MatchDeckMode): MatchTicket? {
        return try {
            val deckCardIds = when (deckMode) {
                MatchDeckMode.SELECTED -> {
                    validateSelectedDeck(userId)
                    null
                }
                MatchDeckMode.RANDOM -> randomDeckService.create(userId)
            }
            val mmr = userService.getMmr(userId).awaitSingle()
            val now = Instant.now(clock)
            val ticket = matchTicketRepository.enqueue(
                MatchTicket(
                    UUID.randomUUID().toString(),
                    userId,
                    mmr,
                    MatchTicketState.QUEUED,
                    1,
                    deckCardIds = deckCardIds,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            applyUserStatus(ticket)
            log.info("User ticket enqueued: userId={}, mmr={}", userId, mmr)
            ticket
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to enqueue user for matching: userId={}", userId, e)
            null
        }
    }

    private suspend fun applyUserStatus(ticket: MatchTicket) {
        when (ticket.state) {
            MatchTicketState.QUEUED, MatchTicketState.ALLOCATING ->
                userService.markMatching(ticket.userId).awaitSingleOrNull()
            MatchTicketState.MATCHED ->
                userService.markPlaying(ticket.userId).awaitSingleOrNull()
            else -> Unit
        }
    }

    private suspend fun validateSelectedDeck(userId: Long) {
        val hasValidDeck = deckService.hasValidSelectedDeck(userId).awaitSingle()
        if (!hasValidDeck) throw IllegalStateException("Deck is invalid or has not been selected")
    }

    suspend fun removeFromQueue(userId: Long): CancelMatchResponse {
        val active = matchTicketRepository.getActive(userId)
            ?: return CancelMatchResponse(CancelMatchResult.NOT_FOUND, null)
        return cancelTicket(userId, active.ticketId)
    }

    suspend fun cancelTicket(userId: Long, ticketId: String): CancelMatchResponse {
        val ticket = matchTicketRepository.cancel(userId, ticketId)
            ?: return CancelMatchResponse(CancelMatchResult.NOT_FOUND, null)
        val result = when (ticket.state) {
            MatchTicketState.CANCELED -> {
                userService.markOnline(userId).awaitSingleOrNull()
                CancelMatchResult.CANCELED
            }
            MatchTicketState.ALLOCATING, MatchTicketState.MATCHED -> CancelMatchResult.TOO_LATE
            else -> CancelMatchResult.ALREADY_FINISHED
        }
        return CancelMatchResponse(result, ticket)
    }

    @Scheduled(fixedRate = 5000)
    fun tryMatching() {
        scope.launch {
            recoverExpiredAllocations()
            expireQueuedTickets()
            val candidates = matchTicketRepository.findCandidates()
            if (candidates.size < 2) return@launch
            val pair = candidates.sortedBy { it.mmr }.zipWithNext().minBy { (left, right) -> right.mmr - left.mmr }
            val now = Instant.now(clock)
            val attemptId = UUID.randomUUID().toString()
            val leaseUntil = now.plus(matchTicketProperties.allocationLease)
            val first = pair.first.copy(state = MatchTicketState.ALLOCATING, version = pair.first.version + 1, attemptId = attemptId, allocationLeaseUntil = leaseUntil, updatedAt = now)
            val second = pair.second.copy(state = MatchTicketState.ALLOCATING, version = pair.second.version + 1, attemptId = attemptId, allocationLeaseUntil = leaseUntil, updatedAt = now)
            if (!matchTicketRepository.claim(first, second)) return@launch

            val uid1 = first.userId
            val uid2 = second.userId
            val sessionId = "session-${matchingQueueRepository.nextSessionId().awaitSingle()}"
            val sessionDto = SessionDto.from(
                sessionId,
                uid1,
                uid2,
                first.deckCardIds,
                second.deckCardIds,
            )

            try {
                val placement = legacyGameMatchService.createSession(sessionDto, attemptId)
                val matchedAt = Instant.now(clock)
                // The host's boot generation rides on the ticket: it is the only way to tell
                // later that the process holding this in-memory session was replaced.
                val completed = matchTicketRepository.transitionPair(
                    first.copy(
                        state = MatchTicketState.MATCHED,
                        version = first.version + 1,
                        matchInfo = placement.matchInfo,
                        serverId = placement.serverId,
                        serverInstanceId = placement.serverInstanceId,
                        allocationLeaseUntil = null,
                        updatedAt = matchedAt,
                    ),
                    second.copy(
                        state = MatchTicketState.MATCHED,
                        version = second.version + 1,
                        matchInfo = placement.matchInfo,
                        serverId = placement.serverId,
                        serverInstanceId = placement.serverInstanceId,
                        allocationLeaseUntil = null,
                        updatedAt = matchedAt,
                    ),
                    MatchTicketState.ALLOCATING,
                )
                check(completed) { "Match tickets changed before completion: attemptId=$attemptId" }
                log.info("Users matched: uid1={}, uid2={}, sessionId={}", uid1, uid2, sessionId)
                userService.markPlaying(uid1).awaitSingleOrNull()
                userService.markPlaying(uid2).awaitSingleOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to create matched session: uid1={}, uid2={}, sessionId={}", uid1, uid2, sessionId, e)
                val failedAt = Instant.now(clock)
                matchTicketRepository.transitionPair(
                    first.copy(state = MatchTicketState.FAILED, version = first.version + 1, reason = "SESSION_CREATION_FAILED", allocationLeaseUntil = null, updatedAt = failedAt),
                    second.copy(state = MatchTicketState.FAILED, version = second.version + 1, reason = "SESSION_CREATION_FAILED", allocationLeaseUntil = null, updatedAt = failedAt),
                    MatchTicketState.ALLOCATING,
                )
                userService.markOnline(uid1).awaitSingleOrNull()
                userService.markOnline(uid2).awaitSingleOrNull()
            }
        }
    }

    private suspend fun recoverExpiredAllocations() {
        val now = Instant.now(clock)
        for (ticket in matchTicketRepository.expiredAllocations()) {
            if (ticket.state != MatchTicketState.ALLOCATING) continue
            val queued = ticket.copy(state = MatchTicketState.QUEUED, version = ticket.version + 1, reason = "ALLOCATION_LEASE_EXPIRED", attemptId = null, allocationLeaseUntil = null, updatedAt = now)
            if (matchTicketRepository.transition(queued, MatchTicketState.ALLOCATING) != null) {
                log.info("Recovered expired allocation: ticketId={}", ticket.ticketId)
            }
        }
    }

    private suspend fun expireQueuedTickets() {
        val now = Instant.now(clock)
        for (ticket in matchTicketRepository.expiredQueued()) {
            val expired = ticket.copy(state = MatchTicketState.EXPIRED, version = ticket.version + 1, reason = "QUEUE_TIMEOUT", updatedAt = now)
            if (matchTicketRepository.transition(expired, MatchTicketState.QUEUED) != null) {
                userService.markOnline(ticket.userId).awaitSingleOrNull()
            }
        }
    }

    suspend fun getQueueLength(): Long = matchTicketRepository.size()

    suspend fun isInQueue(userId: Long): Boolean = matchTicketRepository.getActive(userId)?.state == MatchTicketState.QUEUED

    suspend fun getActiveTicket(userId: Long): MatchTicket? = matchTicketRepository.getActive(userId)

    fun events(userId: Long): Flow<MatchTicket> = matchTicketRepository.events(userId)
}
