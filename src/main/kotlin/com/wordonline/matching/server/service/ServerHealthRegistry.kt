package com.wordonline.matching.server.service

import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.entity.Server
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the discovered game server list together with the health history of each server.
 *
 * Two invariants this class exists to guarantee:
 *
 * - The server list is swapped atomically. Readers always observe a complete snapshot,
 *   never the empty window a `clear()` + `addAll()` pair leaves behind.
 * - Health history is keyed by server id and lives outside the [Server] entity, so
 *   reloading the list from the database does not reset the consecutive failure counters
 *   of servers that survived the reload.
 */
@Component
class ServerHealthRegistry(
    properties: GameServerProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val failureThreshold = properties.failureThreshold.coerceAtLeast(1)

    private val healthStates = ConcurrentHashMap<Long, HealthState>()

    /** Immutable snapshot, replaced wholesale. `@Volatile` publishes it safely to readers. */
    @Volatile
    private var snapshot: List<Server> = emptyList()

    val servers: List<Server>
        get() = snapshot

    /**
     * Atomically replaces the known server list.
     *
     * Health history of servers still present is kept; history of servers that vanished
     * from the database is dropped so the map cannot grow without bound.
     */
    fun replaceServers(discovered: List<Server>) {
        val replacement = discovered.toList()
        snapshot = replacement
        healthStates.keys.retainAll(replacement.mapNotNull { it.id }.toSet())
    }

    /**
     * Records a single health probe result.
     *
     * Hysteresis: one success restores a server immediately, while it takes
     * [GameServerProperties.failureThreshold] consecutive failures to take one out of rotation.
     *
     * [respondingUrl] is the address that actually answered - [Server.internalBaseUrl] when it
     * did, [Server.url] when the probe fell back to the public address. It is only remembered
     * on a successful probe; a failed probe leaves the last known responding address in place
     * rather than clearing it, since nothing reads it while the server is unhealthy.
     */
    fun recordProbe(serverId: Long?, healthy: Boolean, respondingUrl: String? = null) {
        if (serverId == null) return

        val state = healthStates.computeIfAbsent(serverId) { HealthState() }
        val wasHealthy = state.isHealthy()
        state.record(healthy, failureThreshold, respondingUrl)
        val isHealthy = state.isHealthy()

        if (wasHealthy != isHealthy) {
            log.info(
                "Game server {} is now {} (consecutive failures: {})",
                serverId,
                if (isHealthy) "available" else "unavailable",
                state.consecutiveFailures(),
            )
        }
    }

    /**
     * A server is healthy only once a probe has actually succeeded. An unprobed - or never
     * yet successful - server counts as unavailable, because the persisted state column is
     * not trustworthy.
     */
    fun isHealthy(serverId: Long?): Boolean = serverId?.let { healthStates[it]?.isHealthy() } ?: false

    fun consecutiveFailures(serverId: Long?): Int =
        serverId?.let { healthStates[it]?.consecutiveFailures() } ?: 0

    /** The address the last successful health probe actually got an answer from, if any. */
    fun respondingUrl(serverId: Long?): String? = serverId?.let { healthStates[it]?.respondingUrl() }

    /**
     * Address to call [server] on right now. Prefers the address the health check last got an
     * answer from over [Server.callUrl], because a probe result can be more current than the
     * static "internal first" choice: the internal address may be configured but unreachable,
     * something only a real probe - not the field's presence - can tell.
     */
    fun callUrl(server: Server): String = respondingUrl(server.id) ?: server.callUrl

    /** Every server that passed its health check and is not draining. */
    fun availableServers(): List<Server> =
        snapshot.filter { !it.isDraining && isHealthy(it.id) }

    private class HealthState {

        /** Starts unhealthy: a server earns availability by answering, not by existing. */
        private var healthy = false
        private var consecutiveFailures = 0
        private var respondingUrl: String? = null

        @Synchronized
        fun record(probeSucceeded: Boolean, failureThreshold: Int, respondingUrl: String?) {
            if (probeSucceeded) {
                consecutiveFailures = 0
                healthy = true
                if (respondingUrl != null) {
                    this.respondingUrl = respondingUrl
                }
                return
            }
            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) {
                healthy = false
            }
        }

        @Synchronized
        fun isHealthy(): Boolean = healthy

        @Synchronized
        fun consecutiveFailures(): Int = consecutiveFailures

        @Synchronized
        fun respondingUrl(): String? = respondingUrl
    }
}
