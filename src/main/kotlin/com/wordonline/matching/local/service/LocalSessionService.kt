package com.wordonline.matching.local.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.domain.LocalGameOutcome
import com.wordonline.matching.local.domain.LocalResultRejection
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.domain.LocalSessionState
import com.wordonline.matching.local.domain.LocalSessionType
import com.wordonline.matching.local.dto.LocalResultCount
import com.wordonline.matching.local.dto.LocalResultRequest
import com.wordonline.matching.local.dto.LocalResultResponse
import com.wordonline.matching.local.dto.LocalSessionRequest
import com.wordonline.matching.local.dto.LocalSessionResponse
import com.wordonline.matching.local.repository.AcceptedLocalResult
import com.wordonline.matching.local.repository.LocalSessionRepository
import com.wordonline.matching.local.repository.LocalSessionStore
import com.wordonline.matching.local.repository.LocalSessionTransition
import com.wordonline.matching.local.repository.StatisticCount
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.matching.service.PracticeOpponentSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

sealed interface LocalSessionIssueResult {
    data class Issued(val response: LocalSessionResponse) : LocalSessionIssueResult
    data class InvalidRequest(val message: String) : LocalSessionIssueResult
    data object Disabled : LocalSessionIssueResult
    data object ScenarioNotPlayable : LocalSessionIssueResult
    data object NoSelectedDeck : LocalSessionIssueResult
}

sealed interface LocalResultReportResult {
    data class Decided(val response: LocalResultResponse) : LocalResultReportResult
    data class InvalidRequest(val message: String) : LocalResultReportResult
    data object NotFound : LocalResultReportResult
    data object AlreadyReported : LocalResultReportResult
}

/**
 * Issues the sessions the desktop client hosts on its own game server, and checks and records what
 * it reports back. Contract: `.plan/general/2026-10-12-local-hosting-contracts.md`, section 2.
 */
@Service
class LocalSessionService(
    private val properties: LocalSessionProperties,
    private val store: LocalSessionStore,
    private val repository: LocalSessionRepository,
    private val matchingQueueRepository: MatchingQueueRepository,
    private val practiceOpponentSelector: PracticeOpponentSelector,
    private val userService: UserService,
    private val staticDataService: StaticDataService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun issue(userId: Long, request: LocalSessionRequest): LocalSessionIssueResult {
        if (!properties.enabled) return LocalSessionIssueResult.Disabled

        val type = LocalSessionType.entries.firstOrNull { it.name == request.type }
            ?: return LocalSessionIssueResult.InvalidRequest("type must be PRACTICE or PVE")
        val scenarioId = if (type == LocalSessionType.PVE) {
            request.scenarioId ?: return LocalSessionIssueResult.InvalidRequest("scenarioId is required for PVE")
        } else {
            null
        }
        if (scenarioId != null && repository.findScenarioState(userId, scenarioId) !in PLAYABLE_SCENARIO_STATES) {
            return LocalSessionIssueResult.ScenarioNotPlayable
        }

        val deckCardIds = repository.findSelectedDeckCardIds(userId)
        if (deckCardIds.isEmpty()) return LocalSessionIssueResult.NoSelectedDeck

        val opponent = if (type == LocalSessionType.PRACTICE) practiceOpponentSelector.select(userId) else null
        val rightUserId = opponent?.botId ?: PVE_RIGHT_USER_ID
        val sessionId = "local-${matchingQueueRepository.nextSessionId().awaitSingle()}"
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS)

        val response = coroutineScope {
            val leftUser = async { userService.getUserDetail(userId).awaitSingle() }
            // The same right user the shared-server path hands the client: the bot's persona, or for
            // PVE the dummy LegacyGameMatchService builds when there is no second participant.
            val rightUser = async {
                opponent?.let { userService.getUserDetail(it.botId).awaitSingle() } ?: PVE_DUMMY_USER
            }
            val ownedMagicIds = async { repository.findOwnedMagicIds(userId) }
            val noviceProgress = async { repository.findNoviceProgress(userId) ?: COMPLETED_NOVICE_PROGRESS }
            val staticVersion = async { staticDataService.current().staticVersion }
            LocalSessionResponse(
                sessionId = sessionId,
                type = type.name,
                scenarioId = scenarioId,
                leftUserId = userId,
                rightUserId = rightUserId,
                leftUser = leftUser.await(),
                rightUser = rightUser.await(),
                leftDeckCardIds = deckCardIds,
                ownedMagicIds = ownedMagicIds.await(),
                noviceProgress = noviceProgress.await(),
                staticVersion = staticVersion.await(),
                issuedAt = issuedAt,
            )
        }

        store.save(
            LocalSessionRecord(
                sessionId = sessionId,
                userId = userId,
                type = type,
                scenarioId = scenarioId,
                rightUserId = rightUserId,
                hospitalityBot = opponent?.hospitality ?: false,
                issuedAt = issuedAt,
                state = LocalSessionState.ISSUED,
            ),
        )
        log.info("[Local Session] issued sessionId={}, userId={}, type={}, rightUserId={}", sessionId, userId, type, rightUserId)
        return LocalSessionIssueResult.Issued(response)
    }

    /**
     * Checks a reported result and records it when it passes. A rejection is still a 200 answer, so
     * the client does not retry it, and it ends the session like an acceptance does.
     *
     * `WRONG_USER` is the one rejection that leaves the session open: session ids are sequential,
     * and letting any player end someone else's session by guessing its id would throw away the
     * real result.
     */
    suspend fun report(userId: Long, sessionId: String, request: LocalResultRequest): LocalResultReportResult {
        val session = store.find(sessionId) ?: return LocalResultReportResult.NotFound
        val result = when (val validation = validate(request)) {
            is Validation.Valid -> validation.result
            is Validation.Invalid -> return LocalResultReportResult.InvalidRequest(validation.message)
        }

        if (session.userId != userId) {
            return reject(userId, sessionId, request, LocalResultRejection.WRONG_USER)
        }

        when (store.transition(sessionId, LocalSessionState.ISSUED, LocalSessionState.REPORTED)) {
            LocalSessionTransition.MISSING -> return LocalResultReportResult.NotFound
            LocalSessionTransition.STATE_MISMATCH -> return LocalResultReportResult.AlreadyReported
            LocalSessionTransition.TRANSITIONED -> Unit
        }

        val now = clock.instant()
        LocalResultChecks.firstFailure(session, result, now, properties.minimumWinMillis)?.let {
            return reject(userId, sessionId, request, it)
        }

        val day = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (store.incrementDailyAccepted(userId, day) > properties.dailyResultLimit) {
            store.decrementDailyAccepted(userId, day)
            return reject(userId, sessionId, request, LocalResultRejection.DAILY_LIMIT)
        }

        try {
            val gameId = repository.recordAccepted(acceptedResult(session, result))
            log.info("[Local Session] accepted sessionId={}, userId={}, statisticGameId={}", sessionId, userId, gameId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing was written, so the session and the day's count go back to how they were and
            // the client's retry of the same body can still be accepted.
            log.error("[Local Session] failed to record sessionId={}; reopening it", sessionId, e)
            store.decrementDailyAccepted(userId, day)
            store.transition(sessionId, LocalSessionState.REPORTED, LocalSessionState.ISSUED)
            throw e
        }
        return LocalResultReportResult.Decided(LocalResultResponse(accepted = true))
    }

    private fun acceptedResult(session: LocalSessionRecord, result: ValidLocalResult): AcceptedLocalResult {
        val userWon = result.outcome == LocalGameOutcome.WIN && result.winUserId == session.userId
        return AcceptedLocalResult(
            userId = session.userId,
            gameType = session.type.gameType,
            outcome = result.outcome.name,
            winUserId = result.winUserId,
            lossUserId = result.lossUserId,
            durationSeconds = result.durationMillis / 1000,
            serverVersion = result.localServerVersion,
            decks = result.decks,
            magics = result.magics,
            finishScenarioId = session.scenarioId.takeIf { userWon && session.type == LocalSessionType.PVE },
            incrementWins = userWon,
            noviceProgressStep = NOVICE_PROGRESS_PER_WIN.takeIf {
                userWon && session.type == LocalSessionType.PRACTICE && session.hospitalityBot
            },
        )
    }

    private suspend fun reject(
        userId: Long,
        sessionId: String,
        request: LocalResultRequest,
        reason: LocalResultRejection,
    ): LocalResultReportResult {
        log.info("[Local Session] rejected sessionId={}, userId={}, reason={}", sessionId, userId, reason)
        try {
            repository.insertRejection(userId, sessionId, reason.name, objectMapper.writeValueAsString(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The verdict stands without its audit row.
            log.warn("[Local Session] failed to store rejection sessionId={}, reason={}", sessionId, reason, e)
        }
        return LocalResultReportResult.Decided(LocalResultResponse(accepted = false, reason = reason.name))
    }

    private sealed interface Validation {
        data class Valid(val result: ValidLocalResult) : Validation
        data class Invalid(val message: String) : Validation
    }

    private fun validate(request: LocalResultRequest): Validation {
        val outcome = LocalGameOutcome.entries.firstOrNull { it.name == request.outcome }
            ?: return Validation.Invalid("outcome must be WIN, DRAW or ABANDONED")
        val durationMillis = request.durationMillis?.takeIf { it >= 0 }
            ?: return Validation.Invalid("durationMillis must be a non-negative number")
        val frameCount = request.frameCount?.takeIf { it >= 0 }
            ?: return Validation.Invalid("frameCount must be a non-negative number")
        val tickRate = request.tickRate ?: return Validation.Invalid("tickRate is required")
        val localServerVersion = request.localServerVersion?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Validation.Invalid("localServerVersion is required")
        val decks = counts(request.decks) ?: return Validation.Invalid("every decks entry needs userId, magicId and count")
        val magics = counts(request.magics) ?: return Validation.Invalid("every magics entry needs userId, magicId and count")
        return Validation.Valid(
            ValidLocalResult(
                outcome, request.winUserId, request.lossUserId, durationMillis, frameCount, tickRate,
                localServerVersion, decks, magics,
            ),
        )
    }

    private fun counts(entries: List<LocalResultCount>?): List<StatisticCount>? =
        entries.orEmpty().map { entry ->
            StatisticCount(
                entry.userId ?: return null,
                entry.magicId ?: return null,
                entry.count ?: return null,
            )
        }

    companion object {
        private val PLAYABLE_SCENARIO_STATES = setOf("ACTIVE", "FINISHED")

        /** `rightUserId` of a PVE session; the contract fixes it at -1. */
        const val PVE_RIGHT_USER_ID = -1L

        private val PVE_DUMMY_USER = UserDetailResponseDto(0L, "dummy", "dummy@team6515.com")

        /** What the game server answers for a user without a row: nothing held back. */
        private const val COMPLETED_NOVICE_PROGRESS = 1.0

        /** The game server's `UserService.NOVICE_PROGRESS_PER_WIN`. */
        const val NOVICE_PROGRESS_PER_WIN = 0.15
    }
}
