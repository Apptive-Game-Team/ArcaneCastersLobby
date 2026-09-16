package com.wordonline.matching.server.client

import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.dto.RoomListDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Component
class GameServerClient(
    private val webClientBuilder: WebClient.Builder,
    private val properties: GameServerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Java interop boundary: `UserService` is still Reactor-based Java, so this one keeps
     * returning [Mono]. Convert alongside that caller.
     *
     * Collapses every failure - timeout included - to an empty room list so one unresponsive
     * server cannot fail the combined session listing. Callers that need to notice the failure
     * first, to retry on a different address, use [getGameSessionsOrThrow] instead.
     */
    fun getGameSessions(serverUrl: String): Mono<RoomListDto> =
        getGameSessionsOrThrow(serverUrl)
            .onErrorResume { error ->
                log.error("Failed to fetch game sessions from server: {}", serverUrl, error)
                Mono.just(RoomListDto(emptyList()))
            }

    /**
     * Same request as [getGameSessions], but lets a timeout or any other failure propagate
     * instead of collapsing it to an empty list, so [GameSessionService] can retry once on the
     * server's public address before giving up.
     */
    fun getGameSessionsOrThrow(serverUrl: String): Mono<RoomListDto> {
        log.info("Fetching game sessions from server: {}", serverUrl)

        // The timeout stays *above* the caller's error handling so an unresponsive server
        // surfaces as a TimeoutException instead of hanging past the intended budget.
        // clone() first: baseUrl() mutates the builder and returns it, and this component is a
        // singleton whose callers probe many servers at once. Without the copy a concurrent call
        // overwrites the base URL and the request lands on another server - which was observed as
        // a healthcheck logging one host while the failure came back from a different one.
        return webClientBuilder.clone().baseUrl(serverUrl).build()
            .get()
            .uri("/api/server/game-sessions")
            .retrieve()
            .bodyToMono(RoomListDto::class.java)
            .timeout(properties.sessionsTimeout)
    }

    /**
     * Probes the game server health endpoint.
     *
     * Returns `true` only when the server answered 2xx in time. Every failure mode -
     * timeout, connection refused, non-2xx - collapses to `false`; this never throws.
     * That matters because the caller probes many servers concurrently and one bad
     * server must not abort the others.
     *
     * The timeout is expressed with [withTimeoutOrNull] rather than a Reactor `timeout`
     * operator, which makes the original bug (a timeout signalled *below* the error
     * handler and therefore escaping it) structurally impossible.
     */
    suspend fun healthcheck(serverUrl: String): Boolean {
        val healthy = try {
            // withTimeoutOrNull takes millis or a kotlin.time.Duration, not java.time.Duration.
            withTimeoutOrNull(properties.healthcheckTimeout.toMillis()) {
                webClientBuilder.clone().baseUrl(serverUrl).build()
                    .get()
                    .uri("/healthcheck")
                    .retrieve()
                    .toBodilessEntity()
                    .awaitSingle()
                    .statusCode
                    .is2xxSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Healthcheck failed for server: {} ({})", serverUrl, e.toString())
            return false
        }

        if (healthy == null) {
            log.warn("Healthcheck timed out after {} for server: {}", properties.healthcheckTimeout, serverUrl)
            return false
        }
        return healthy
    }
}
