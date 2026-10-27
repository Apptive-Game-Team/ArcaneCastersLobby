package com.wordonline.matching.matching.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.config.LearnedBotProperties
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.nio.file.Files
import java.nio.file.Path

@Service
class LearnedBotAvailability(
    private val builder: WebClient.Builder,
    private val servers: GameServerManagementService,
    private val properties: LearnedBotProperties,
    mapper: ObjectMapper,
) {
    // 모델 파일이 없어도 지정 ID를 유지해야 RULE 봇으로 잘못 매칭하지 않습니다.
    private val assignments: Map<Long, String> = if (properties.config.isBlank()) emptyMap() else {
        val path = Path.of(properties.config)
        require(Files.size(path) <= 128 * 1024) { "learned bot config too large" }
        val node = mapper.readTree(Files.readString(path)).get("assignments")
        require(node != null && node.isObject && node.size() <= 256) { "invalid learned bot assignments" }
        node.properties().associate { (key, value) ->
            val id = key.toLong()
            require(id < 0 && value.isTextual && value.asText().matches(Regex("[a-z0-9-]{1,80}"))) {
                "invalid learned bot assignment"
            }
            id to value.asText()
        }
    }

    init {
        require(!properties.timeout.isNegative && !properties.timeout.isZero) { "invalid learned bot timeout" }
    }

    suspend fun unavailableBotIds(): List<Long> = coroutineScope {
        if (assignments.isEmpty()) return@coroutineScope emptyList()
        val ready = servers.getAvailableServers().map { server ->
            async { availableAt(servers.callUrl(server)) }
        }.awaitAll()
        assignments.filter { (id, version) -> ready.none { it[id] == version } }.keys.toList()
    }

    suspend fun permits(serverUrl: String, ids: List<Long>): Boolean {
        val required = assignments.filterKeys { it in ids }
        if (required.isEmpty()) return true
        val ready = availableAt(serverUrl)
        return required.all { (id, version) -> ready[id] == version }
    }

    private suspend fun availableAt(url: String): Map<Long, String> = try {
        builder.clone().baseUrl(url).build().get().uri("/api/server/learned-bots")
            .retrieve().bodyToMono(object : ParameterizedTypeReference<Map<Long, String>>() {})
            .timeout(properties.timeout).awaitSingle()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 구버전 서버의 404와 인증·연결 실패도 준비되지 않은 상태입니다.
        emptyMap()
    }
}
