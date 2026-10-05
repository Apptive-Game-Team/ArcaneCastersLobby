package com.wordonline.matching.playground

import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Service
@ConditionalOnProperty(name = ["playground.enabled"], havingValue = "true")
class PlaygroundService(
    private val servers: GameServerManagementService,
    private val builder: WebClient.Builder,
    private val properties: PlaygroundProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun create(ownerId: Long): PlaygroundInfo {
        if (ownerId <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid developer.")
        for (server in servers.getAvailableServers()) {
            try {
                // Only an explicitly enabled game server exposes this route. Its ready
                // response supplies public connection URLs even when we call an internal URL.
                val ready = withTimeoutOrNull(properties.requestTimeout.toMillis()) {
                    builder.clone().baseUrl(servers.callUrl(server)).build()
                        .post().uri("/api/server/playgrounds")
                        .bodyValue(CreatePlaygroundRequest(ownerId))
                        .retrieve().bodyToMono(PlaygroundInfo::class.java).awaitSingle()
                } ?: continue
                if (ready.ownerId != ownerId || !ready.sessionId.startsWith("playground-") ||
                    ready.server.isBlank() || ready.webSocketUrl.isBlank() ||
                    !ready.expiresAt.isAfter(Instant.now())
                ) continue
                // Deliberately no ticket, user status, deck lookup or SessionRecoveryStore.
                // There is no persisted lobby state to strand after expiry/failure.
                return ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Game server {} cannot host a playground: {}", server.id, e.javaClass.simpleName)
            }
        }
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No development playground server is available.")
    }
}
