package com.wordonline.matching.matching.dto

import com.wordonline.matching.matching.domain.CancelMatchResponse
import com.wordonline.matching.matching.domain.CancelMatchResult
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import java.time.Instant

data class MatchTicketResponse(
    val ticketId: String,
    val userId: Long,
    val mmr: Long,
    val state: MatchTicketState,
    val version: Long,
    val reason: String?,
    val matchInfo: MatchedInfoDto?,
    val attemptId: String?,
    val allocationLeaseUntil: Instant?,
    val serverId: Long?,
    val serverInstanceId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(ticket: MatchTicket) = MatchTicketResponse(
            ticket.ticketId,
            ticket.userId,
            ticket.mmr,
            ticket.state,
            ticket.version,
            ticket.reason,
            ticket.matchInfo,
            ticket.attemptId,
            ticket.allocationLeaseUntil,
            ticket.serverId,
            ticket.serverInstanceId,
            ticket.createdAt,
            ticket.updatedAt,
        )
    }
}

data class CancelMatchResponseDto(
    val result: CancelMatchResult,
    val ticket: MatchTicketResponse?,
) {
    companion object {
        fun from(response: CancelMatchResponse) = CancelMatchResponseDto(
            response.result,
            response.ticket?.let(MatchTicketResponse::from),
        )
    }
}
