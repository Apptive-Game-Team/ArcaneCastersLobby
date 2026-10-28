package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.SessionLiveness
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.exception.GameServerUnreachableException
import com.wordonline.matching.server.service.ServerHealthRegistry
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Decides whether the session behind a `MATCHED` ticket still exists.
 *
 * Two independent signals, cheapest first:
 *
 * 1. Boot generation. A game server keeps its sessions in memory only, so a process that
 *    restarted took them with it. The id the ticket recorded at match time versus the id
 *    the server row publishes now proves that without any network call - and it stays
 *    correct even when the restarted process is already answering health checks again.
 * 2. Session query against the hosting server. Authoritative when the host answers.
 *
 * The same two signals also separate a finished game from a lost one. A host that is
 * provably the process which accepted the session and reports the session gone has simply
 * finished it ([SessionLiveness.ENDED]); anything else that ends a session is a loss.
 *
 * Every inconclusive path collapses to [SessionLiveness.UNKNOWN]. A missing id on either
 * side is an older game server build, not evidence of a restart - and not proof of same
 * process either, so it downgrades a finished game to [SessionLiveness.LOST] rather than
 * claiming a clean ending it cannot see.
 */
@Component
class SessionLivenessProbe(
    private val legacyGameMatchService: LegacyGameMatchService,
    private val serverHealthRegistry: ServerHealthRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun check(ticket: MatchTicket): SessionLiveness {
        val matchInfo = ticket.matchInfo ?: return SessionLiveness.UNKNOWN
        val generation = bootGeneration(ticket)

        if (generation == BootGeneration.DIFFERENT) {
            log.info(
                "Host process restarted since match: ticketId={}, sessionId={}, ticketInstanceId={}",
                ticket.ticketId,
                matchInfo.sessionId,
                ticket.serverInstanceId,
            )
            return SessionLiveness.LOST
        }

        return try {
            val active = if (matchInfo.lockstepSimulationVersion == null) {
                legacyGameMatchService.isSessionActive(matchInfo.server, matchInfo.sessionId)
            } else {
                legacyGameMatchService.isSessionActive(matchInfo.server, matchInfo.sessionId, matchInfo.lockstepSimulationVersion)
            }
            when {
                active -> SessionLiveness.ALIVE
                // Same process, session gone: it ran the game to its end rather than losing it.
                generation == BootGeneration.SAME -> SessionLiveness.ENDED
                else -> SessionLiveness.LOST
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GameServerUnreachableException) {
            log.warn("Host {} did not answer about session {}", matchInfo.server, matchInfo.sessionId, e)
            SessionLiveness.UNKNOWN
        }
    }

    /**
     * Whether the process answering for this ticket's host is still the one that accepted the
     * session. Conclusive only when both sides reported an id; a `null` on either side is an
     * older game server build and proves neither a restart nor a survival.
     */
    fun bootGeneration(ticket: MatchTicket): BootGeneration {
        val recorded = ticket.serverInstanceId ?: return BootGeneration.UNKNOWN
        val current = hostOf(ticket)?.instanceId ?: return BootGeneration.UNKNOWN
        return if (recorded == current) BootGeneration.SAME else BootGeneration.DIFFERENT
    }

    /**
     * The server row hosting this ticket's session.
     *
     * Matched by id first, which is what the ticket records at match time. The URL fallback
     * covers tickets written before [MatchTicket.serverId] existed.
     */
    fun hostOf(ticket: MatchTicket): Server? {
        val servers = serverHealthRegistry.servers
        ticket.serverId?.let { id -> servers.firstOrNull { it.id == id }?.let { return it } }
        val hostUrl = ticket.matchInfo?.server ?: return null
        return servers.firstOrNull { runCatching { it.url }.getOrNull() == hostUrl }
    }
}

/** Relationship between the boot generation a ticket recorded and the one the host publishes now. */
enum class BootGeneration {
    SAME,
    DIFFERENT,
    UNKNOWN,
}
