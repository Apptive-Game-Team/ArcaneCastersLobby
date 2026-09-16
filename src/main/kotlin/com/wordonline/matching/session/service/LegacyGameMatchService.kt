package com.wordonline.matching.session.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.global.service.LocalizationService
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.exception.GameServerUnreachableException
import com.wordonline.matching.server.exception.NoAvailableGameServerException
import com.wordonline.matching.server.service.GameServerManagementService
import com.wordonline.matching.session.domain.SessionPlacement
import com.wordonline.matching.session.domain.SessionRecoveryInfo
import com.wordonline.matching.session.dto.SimpleBooleanDto
import com.wordonline.matching.session.dto.CreateSessionRequest
import com.wordonline.matching.session.dto.SessionReadyResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContext
import org.springframework.context.i18n.SimpleLocaleContext
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.Locale
import java.util.UUID

@Service
class LegacyGameMatchService(
    private val webClientBuilder: WebClient.Builder,
    private val sessionRecoveryStore: SessionRecoveryStore,
    private val localizationService: LocalizationService,
    private val userService: UserService,
    private val gameServerManagementService: GameServerManagementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Places a session on the first game server that accepts it.
     *
     * Candidates are tried in order and a refusal is not fatal: a game server that has
     * already flipped itself out of `ACTIVE` answers `false`, and a redeploying one drops
     * the connection. Both used to fail the whole match even when another healthy server
     * was sitting right there.
     *
     * Order matters: availability check, then user lookup, then the candidate loop. With no
     * server at all the user lookup is wasted work, but a failed user lookup must never leave
     * an orphan room behind on a game server, so it has to finish before the first offer goes
     * out. Looking the users up once outside the loop also keeps a failover from re-querying
     * the account server per candidate.
     */
    suspend fun createSession(sessionDto: SessionDto, attemptId: String = UUID.randomUUID().toString()): SessionPlacement {
        val candidates = gameServerManagementService.getAvailableServers()
        if (candidates.isEmpty()) {
            throw NoAvailableGameServerException(localizedMessage("error.gameserver.unavailable"))
        }

        val (leftUser, rightUser) = userDetails(sessionDto.uid1, sessionDto.uid2)

        for (server in candidates) {
            val ready = offerSession(server, sessionDto, attemptId) ?: continue

            log.info(
                "Session created on game server {}: sessionId={}",
                gameServerManagementService.callUrl(server),
                sessionDto.sessionId,
            )
            // the URL must come from the server that actually accepted, not from the first candidate
            val matchedInfo = MatchedInfoDto(
                "Successfully Matched",
                ready.serverUrl,
                leftUser,
                rightUser,
                sessionDto.sessionId,
                ready.webSocketUrl,
            )
            sessionRecoveryStore.storeMatchInfo(matchedInfo).awaitSingleOrNull()
            return SessionPlacement(matchedInfo, server.id, ready.instanceId)
        }

        log.error(
            "Every game server refused the session: sessionId={}, candidates={}",
            sessionDto.sessionId,
            candidates.size,
        )
        throw NoAvailableGameServerException(localizedMessage("error.gameserver.unavailable"))
    }

    /**
     * Offers the session on [server]'s call address and returns only a validated ready response;
     * refusal, stale response, and transport failure allow failover. [Server.url] is not used
     * for the primary attempt: it is reserved for the address handed back to the client.
     *
     * When the primary attempt goes out on the internal address and fails with a timeout or
     * any other exception, this retries exactly once on the public address - the health check
     * that chose the internal address can be stale, since a game server can go unreachable
     * internally after its last successful probe. A primary attempt that was already on the
     * public address never retries: that would send the identical request twice.
     */
    private suspend fun offerSession(server: Server, sessionDto: SessionDto, attemptId: String): SessionReadyResponse? {
        val callUrl = gameServerManagementService.callUrl(server)
        val response = try {
            requestSession(callUrl, sessionDto, attemptId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (callUrl == server.url) {
                log.warn("Game server {} failed to take session {}: {}", callUrl, sessionDto.sessionId, e.toString())
                return null
            }

            log.warn(
                "Game server {} failed to take session {} via internal address {}; retrying on public address {}: {}",
                server.id,
                sessionDto.sessionId,
                callUrl,
                server.url,
                e.toString(),
            )
            try {
                requestSession(server.url, sessionDto, attemptId)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                log.warn(
                    "Game server {} failed to take session {} on public address {} too: {}",
                    server.id,
                    sessionDto.sessionId,
                    server.url,
                    e2.toString(),
                )
                return null
            }
        }

        return if (!response.ready || response.attemptId != attemptId || response.sessionId != sessionDto.sessionId) {
            log.warn("Game server {} returned invalid readiness for session {}", callUrl, sessionDto.sessionId)
            null
        } else {
            response
        }
    }

    private suspend fun requestSession(url: String, sessionDto: SessionDto, attemptId: String): SessionReadyResponse =
        // clone() first: baseUrl() mutates the shared builder, and session offers run concurrently
        // across candidate servers. See GameServerClient for the failure this prevents.
        webClientBuilder.clone().baseUrl(url).build()
            .post()
            .uri("/api/server/game-sessions")
            .bodyValue(CreateSessionRequest(attemptId, sessionDto))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(SessionReadyResponse::class.java)
            .awaitSingle()

    suspend fun getMatchInfo(userId: Long): MatchedInfoDto {
        val sessionInfo = sessionRecoveryStore.getSessionInfo(userId).awaitSingleOrNull()
            ?: throw IllegalArgumentException("Session Not Found")

        // ask the server that hosts this session, not an arbitrary available one
        if (!isSessionActive(sessionInfo.serverUrl(), sessionInfo.sessionId())) {
            throw IllegalArgumentException("Session Already Deactivated")
        }

        val (leftUser, rightUser) = userDetails(sessionInfo.leftUserId(), sessionInfo.rightUserId())
        return MatchedInfoDto(sessionInfo, leftUser, rightUser)
    }

    /**
     * A host that does not answer is a transient failure, not proof the session ended, so
     * this throws [GameServerUnreachableException] instead of reporting the session gone.
     */
    suspend fun isSessionActive(serverUrl: String, sessionId: String): Boolean =
        try {
            webClientBuilder.clone().baseUrl(serverUrl).build()
                .get()
                .uri("/api/server/game-sessions/$sessionId/active")
                .retrieve()
                .bodyToMono(SimpleBooleanDto::class.java)
                .awaitSingle()
                .value()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Host {} did not answer for session {}: {}", serverUrl, sessionId, e.toString())
            throw GameServerUnreachableException(localizedMessage("error.gameserver.unreachable"), e)
        }

    private suspend fun userDetails(
        userId1: Long,
        userId2: Long?,
    ): Pair<UserDetailResponseDto, UserDetailResponseDto> = coroutineScope {
        val left = async { userService.getUserDetail(userId1).awaitSingle() }
        val right = async {
            if (userId2 != null) userService.getUserDetail(userId2).awaitSingle()
            else UserDetailResponseDto(0L, "dummy", "dummy@team6515.com")
        }
        left.await() to right.await()
    }

    /**
     * The request locale rides in the Reactor context (see `LocaleContextWebFilter`), which
     * Spring propagates into suspending handlers. The scheduled matching loop has no request
     * behind it, so the absent context falls back to the default locale instead of throwing -
     * the previous `ctx.get(LocaleContext.class)` would have blown up on that path.
     */
    private suspend fun localizedMessage(code: String): String {
        val localeContext: LocaleContext = currentCoroutineContext()[ReactorContext]
            ?.context
            ?.getOrEmpty<LocaleContext>(LocaleContext::class.java)
            ?.orElse(null)
            ?: SimpleLocaleContext(Locale.getDefault())
        return localizationService.getMessage(localeContext, code)
    }
}
