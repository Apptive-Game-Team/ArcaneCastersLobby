package com.wordonline.matching.preview

import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.CancellationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Service
class PreviewService(private val servers: GameServerManagementService, private val client: PreviewClient) {
    suspend fun catalog(): PreviewCatalog {
        val server = servers.getAvailableServer() ?: return PreviewCatalog("unavailable", null)
        return try {
            client.catalog(servers.callUrl(server)).copy(serverId = server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreviewCatalog("unavailable", null)
        }
    }

    suspend fun recording(serverId: Long, name: String, revision: String, hash: String): ByteArray {
        if (!name.matches(Regex("[a-z0-9_]{1,100}")) || !hash.matches(Regex("[a-f0-9]{64}")) ||
            !revision.matches(Regex("[a-f0-9]{64}")))
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val server = servers.getAvailableServers().firstOrNull { it.id == serverId }
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val bytes = try {
            client.recording(servers.callUrl(server), name, revision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebClientResponseException) {
            // A redeploy or invalidation must be visible to the client, never silently re-routed.
            val status = when (e.statusCode.value()) {
                404 -> HttpStatus.NOT_FOUND
                409 -> HttpStatus.CONFLICT
                else -> HttpStatus.SERVICE_UNAVAILABLE
            }
            throw ResponseStatusException(status)
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != hash) throw ResponseStatusException(HttpStatus.CONFLICT)
        return bytes
    }
}
