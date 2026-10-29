package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.repository.MatchTicketRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Handles the game server's `POST /api/internal/game-sessions/{sessionId}/ended`.
 *
 * Without it a finished game closes nothing: the game server only flips `user_status` back to
 * `Online` and the lobby ticket stays `MATCHED`, which makes the next enqueue silently return
 * the old ticket instead of queueing. [MatchedTicketReconciler] would eventually notice, but
 * only after `matched-scan-grace` plus a scan interval - a minute of a player pressing a
 * button that does nothing.
 *
 * The notification is a shortcut, never the only path. Every check here fails closed: an
 * unrecognised session, a stale sender or a ticket that moved on all leave the reconciler to
 * reach the same verdict on its own schedule.
 */
@Service
class SessionEndedNotificationService(
    private val matchTicketRepository: MatchTicketRepository,
    private val sessionLivenessProbe: SessionLivenessProbe,
    private val lostSessionRecovery: LostSessionRecovery,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Retires both tickets of [sessionId] as `FAILED(SESSION_ENDED)` and puts the players back
     * online.
     *
     * Idempotent by construction: the second notification finds no `MATCHED` ticket left on
     * that session and does nothing. It never reports failure to the game server either - the
     * game is over there regardless of what the lobby could do about it, and a retry loop on
     * the game side would only delay its own shutdown.
     */
    suspend fun sessionEnded(sessionId: String, instanceId: String) {
        val ticket = matchTicketRepository.matchedBySession(sessionId).firstOrNull()
        if (ticket == null) {
            // Already retired, never matched through a ticket, or the ticket expired.
            log.debug("No matched ticket to retire for ended session {}", sessionId)
            return
        }

        if (isStale(ticket, instanceId)) {
            log.info(
                "Ignoring session-ended notification from a replaced process: sessionId={}, senderInstanceId={}",
                sessionId,
                instanceId,
            )
            return
        }

        val released = lostSessionRecovery.release(ticket, LostSessionRecovery.SESSION_ENDED_REASON)
        if (released == null) {
            log.info("Ticket moved on while closing ended session: sessionId={}, ticketId={}", sessionId, ticket.ticketId)
            return
        }
        log.info("Closed ended session on notification: sessionId={}, userId={}", sessionId, ticket.userId)
    }

    /**
     * Whether the sender is provably not the process that owns this session.
     *
     * Two independent generations can disprove it, and either is enough:
     *
     * - the one the ticket recorded when the session was placed - the process that accepted it;
     * - the one the hosting server row publishes now - the process that is running there.
     *
     * A `null` on either side is an older game server build, so it disproves nothing and the
     * notification is honoured. Sessions live in memory, so a notification from a process that
     * no longer exists can only be a late duplicate: dropping it leaves the ticket to the
     * reconciler, which will find the boot generation changed and record a loss instead.
     */
    private fun isStale(ticket: MatchTicket, instanceId: String): Boolean {
        val accepted = ticket.serverInstanceId
        val current = sessionLivenessProbe.hostOf(ticket)?.instanceId
        // lockstep의 outbox는 placement generation을 보존하며 호스트 재시작 뒤에도 재전송한다.
        // 티켓에 기록된 placement와 다른 송신자는 계속 차단한다.
        val lockstep = ticket.matchInfo?.lockstepSimulationVersion != null
        return (accepted != null && accepted != instanceId) || (!lockstep && current != null && current != instanceId)
    }
}
