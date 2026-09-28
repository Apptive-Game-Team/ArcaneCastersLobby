package com.wordonline.matching.matching.domain

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.matching.dto.MatchedInfoDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Instant

/**
 * A ticket that reached MATCHED carries [MatchedInfoDto], and every read path
 * (`GET /api/match/tickets/active`, the SSE stream, lease recovery) decodes it back out of
 * Redis. [MatchedInfoDto] has several constructors, so it has no implicit creator for
 * jackson-module-parameter-names to pick and only jackson-module-kotlin can bind it.
 */
class MatchTicketSerializationTest {
    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()

    @Test
    fun `matched ticket survives a redis round trip`() {
        val ticket = MatchTicket(
            ticketId = "ticket-1",
            userId = 1L,
            mmr = 1200L,
            state = MatchTicketState.MATCHED,
            version = 3L,
            matchInfo = MatchedInfoDto(
                message = "Successfully Matched",
                server = "http://localhost:7777",
                leftUser = UserDetailResponseDto(1L, "left", "left@example.com"),
                rightUser = UserDetailResponseDto(2L, "right", "right@example.com"),
                sessionId = "session-1",
                webSocketUrl = "http://localhost:7777/ws",
            ),
            attemptId = "attempt-1",
            serverId = 7L,
            serverInstanceId = "boot-1",
            deckCardIds = listOf(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 6L, 6L, 7L, 7L, 8L),
            createdAt = Instant.parse("2026-08-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-10T00:00:05Z"),
        )

        val json = objectMapper.writeValueAsString(ticket)
        val decoded = objectMapper.readValue(json, MatchTicket::class.java)

        assertThat(decoded).isEqualTo(ticket)
        assertThat(decoded.matchInfo?.server).isEqualTo("http://localhost:7777")
        assertThat(decoded.matchInfo?.webSocketUrl).isEqualTo("http://localhost:7777/ws")
        assertThat(decoded.serverInstanceId)
            .`as`("호스트 재시작 판별의 유일한 근거이므로 왕복에서 사라지면 안 된다")
            .isEqualTo("boot-1")
        assertThat(decoded.serverId).isEqualTo(7L)
        assertThat(decoded.deckCardIds).isEqualTo(ticket.deckCardIds)
    }

    @Test
    fun `derived opponent id is not written into the payload`() {
        // A derived value in the payload would be read back as stored state on the next
        // decode and could contradict matchInfo after a re-match.
        val ticket = MatchTicket(
            ticketId = "ticket-3",
            userId = 1L,
            mmr = 1200L,
            state = MatchTicketState.MATCHED,
            version = 3L,
            matchInfo = MatchedInfoDto(
                message = "Successfully Matched",
                server = "http://localhost:7777",
                leftUser = UserDetailResponseDto(1L, "left", "left@example.com"),
                rightUser = UserDetailResponseDto(2L, "right", "right@example.com"),
                sessionId = "session-1",
            ),
            createdAt = Instant.parse("2026-08-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-10T00:00:05Z"),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(ticket))

        assertThat(json.has("opponentUserId")).isFalse()
        assertThat(ticket.opponentUserId).isEqualTo(2L)
    }

    @Test
    fun `queued ticket without match info survives a redis round trip`() {
        val ticket = MatchTicket(
            ticketId = "ticket-2",
            userId = 2L,
            mmr = 1100L,
            state = MatchTicketState.QUEUED,
            version = 1L,
            createdAt = Instant.parse("2026-08-10T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-10T00:00:00Z"),
        )

        val decoded = objectMapper.readValue(objectMapper.writeValueAsString(ticket), MatchTicket::class.java)

        assertThat(decoded).isEqualTo(ticket)
        assertThat(decoded.matchInfo).isNull()
    }
}
