package com.wordonline.matching.matching.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.matching.config.MatchEventStreamProperties
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.CancelMatchResponseDto
import com.wordonline.matching.matching.dto.MatchTicketRequest
import com.wordonline.matching.matching.dto.MatchTicketResponse
import com.wordonline.matching.matching.dto.QueueLengthResponseDto
import com.wordonline.matching.matching.dto.SimpleMessageDto
import com.wordonline.matching.matching.service.GameMatchService
import com.wordonline.matching.matching.service.SessionLostReportService
import com.wordonline.matching.matching.domain.SessionLostReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestBody

@RestController
class MatchingController(
    private val gameMatchService: GameMatchService,
    private val sessionLostReportService: SessionLostReportService,
    private val matchEventStreamProperties: MatchEventStreamProperties,
) {
    @GetMapping("/api/match/practice/me")
    suspend fun matchPractice(@UserId userId: Long?): MatchedInfoDto {
        return gameMatchService.matchPractice(userId!!)
    }

    @GetMapping("/api/match/queue/me")
    suspend fun match(@UserId userId: Long?): SimpleMessageDto {
        return gameMatchService.match(userId!!)
    }

    @PostMapping("/api/match/tickets")
    suspend fun createTicket(
        @UserId userId: Long?,
        @RequestBody(required = false) request: MatchTicketRequest?,
    ): MatchTicketResponse = MatchTicketResponse.from(
        gameMatchService.createTicket(userId!!, request?.deckMode ?: MatchTicketRequest().deckMode),
    )

    @GetMapping("/api/match/queue/me/exist")
    suspend fun isMeInQueue(@UserId userId: Long?): ResponseEntity<Unit> {
        return if (gameMatchService.isInQueue(userId!!)) {
            ResponseEntity.ok().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping("/api/match/queue/me")
    suspend fun removeFromQueue(@UserId userId: Long?): CancelMatchResponseDto =
        CancelMatchResponseDto.from(gameMatchService.removeFromQueue(userId!!))

    @DeleteMapping("/api/match/tickets/{ticketId}")
    suspend fun cancelTicket(
        @UserId userId: Long?,
        @PathVariable ticketId: String,
    ): CancelMatchResponseDto = CancelMatchResponseDto.from(gameMatchService.cancelTicket(userId!!, ticketId))

    @GetMapping("/api/match/tickets/active")
    suspend fun getActiveTicket(@UserId userId: Long?): ResponseEntity<MatchTicketResponse> {
        val ticket = gameMatchService.getActiveTicket(userId!!)
        return if (ticket == null) ResponseEntity.notFound().build()
        else ResponseEntity.ok(MatchTicketResponse.from(ticket))
    }

    /**
     * Reports that the client can no longer reach its session.
     *
     * Session-scoped rather than ticket-scoped because the client comes through the legacy
     * match flow and holds a `sessionId` but no `ticketId`.
     *
     * - `200` the ticket is released, or there was nothing to release
     * - `409` the session is still running; the returned snapshot is the live ticket
     * - `404` no active ticket, or it belongs to a different session
     * - `503` the host did not answer, so nothing is proven and the ticket is left alone
     */
    @PostMapping("/api/match/sessions/{sessionId}/report-lost")
    suspend fun reportSessionLost(
        @UserId userId: Long?,
        @PathVariable sessionId: String,
    ): ResponseEntity<MatchTicketResponse> = when (val report = sessionLostReportService.report(userId!!, sessionId)) {
        is SessionLostReport.Released -> ResponseEntity.ok(MatchTicketResponse.from(report.ticket))
        is SessionLostReport.NothingToRelease -> ResponseEntity.ok(MatchTicketResponse.from(report.ticket))
        is SessionLostReport.SessionAlive -> ResponseEntity.status(HttpStatus.CONFLICT).body(MatchTicketResponse.from(report.ticket))
        SessionLostReport.UnknownSession -> ResponseEntity.notFound().build()
    }

    /**
     * Pushes ticket updates to the client.
     *
     * The stream is silent between updates, which is what breaks it in production: a
     * buffering proxy holds frames until its buffer fills, and an idle-timeout proxy drops
     * the connection outright. Both leave the client waiting on a match it has already been
     * given. The keep-alive comments and the no-buffering headers below exist for that, not
     * for the protocol.
     */
    @GetMapping("/api/match/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@UserId userId: Long?, response: ServerHttpResponse): Flow<ServerSentEvent<MatchTicketResponse>> {
        // nginx buffers a proxied response by default; `no-transform` stops a proxy from
        // gzipping the stream, which would buffer it just as effectively.
        response.headers.set("X-Accel-Buffering", "no")
        response.headers.cacheControl = "no-cache, no-transform"

        val ticketUpdates = gameMatchService.events(userId!!).map { ticket ->
            ServerSentEvent.builder(MatchTicketResponse.from(ticket))
                .id(ticket.version.toString())
                .event("match-ticket-updated")
                .build()
        }
        return merge(heartbeats(), ticketUpdates)
    }

    /**
     * Emits once immediately so the response headers and a first byte reach the client before
     * any ticket update, then keeps the stream warm.
     */
    private fun heartbeats(): Flow<ServerSentEvent<MatchTicketResponse>> = flow {
        val heartbeat = ServerSentEvent.builder<MatchTicketResponse>().comment("keep-alive").build()
        while (true) {
            emit(heartbeat)
            delay(matchEventStreamProperties.heartbeatInterval.toMillis())
        }
    }

    @GetMapping("/api/match/length")
    suspend fun getQueueLength(): QueueLengthResponseDto {
        return QueueLengthResponseDto(gameMatchService.getQueueLength().toInt())
    }
}
