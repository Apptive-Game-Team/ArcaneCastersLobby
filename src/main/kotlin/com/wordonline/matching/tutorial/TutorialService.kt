package com.wordonline.matching.tutorial

import com.wordonline.matching.progression.ProgressionRepository
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ConfigurationProperties(prefix = "tutorial")
data class TutorialProperties(
    val requestTimeout: Duration = Duration.ofSeconds(5),
    val affinityLifetime: Duration = Duration.ofMinutes(20),
    val maxPending: Int = 4096,
)

data class TutorialReady(
    val battleId: String,
    val ownerId: Long,
    val faction: String,
    val server: String,
    val expiresAt: Instant,
)

data class CreateTutorial(val requestId: String)
data class CreateGameTutorial(val ownerId: Long, val faction: String, val requestId: String)

@Service
@EnableConfigurationProperties(TutorialProperties::class)
class TutorialService(
    private val progression: ProgressionRepository,
    private val servers: GameServerManagementService,
    private val builder: WebClient.Builder,
    private val properties: TutorialProperties,
) {
    private data class Route(val server: Server, val expires: Instant)
    private val routes = mutableMapOf<Long, Route>()

    suspend fun create(ownerId: Long, request: CreateTutorial): TutorialReady {
        if (ownerId <= 0) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        try { UUID.fromString(request.requestId) }
        catch (invalid: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request id") }
        val faction = progression.faction(ownerId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Choose a starting faction first")
        if (faction !in setOf("HELL", "NATURE")) throw ResponseStatusException(HttpStatus.CONFLICT, "Unknown faction")
        val server = route(ownerId)
        // 응답을 받지 못해도 같은 요청은 같은 서버로 보내 전투가 둘 생기지 않게 한다.
        val ready = withTimeout(properties.requestTimeout.toMillis()) {
            builder.clone().baseUrl(servers.callUrl(server)).build().post()
                .uri("/api/server/tutorial/battles")
                .bodyValue(CreateGameTutorial(ownerId, faction, request.requestId))
                .retrieve().bodyToMono(TutorialReady::class.java).awaitSingle()
        }
        if (ready.ownerId != ownerId || ready.faction != faction || !ready.battleId.startsWith("tutorial-") ||
            ready.server.isBlank() || !ready.expiresAt.isAfter(Instant.now()))
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid tutorial ready response")
        return ready
    }

    private fun route(ownerId: Long): Server = synchronized(routes) {
        val now = Instant.now()
        routes.entries.removeIf { !it.value.expires.isAfter(now) }
        routes[ownerId]?.let { return@synchronized it.server }
        if (routes.size >= properties.maxPending) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)
        val available = servers.getAvailableServers().sortedBy { it.id }
        if (available.isEmpty()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No tutorial server available")
        val selected = available[Math.floorMod(ownerId.hashCode(), available.size)]
        routes[ownerId] = Route(selected, now.plus(properties.affinityLifetime))
        selected
    }
}
