package com.wordonline.matching.matching.controller

import com.wordonline.matching.matching.config.MatchEventStreamProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchTicketResponse
import com.wordonline.matching.matching.service.GameMatchService
import com.wordonline.matching.matching.service.SessionLostReportService
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import java.time.Duration
import java.time.Instant

/**
 * A silent SSE stream is indistinguishable from a working one until a match is missed, so the
 * keep-alive and the anti-buffering headers are the parts worth pinning down.
 */
class MatchEventStreamTest {

    private val gameMatchService: GameMatchService = mock()
    private val sessionLostReportService: SessionLostReportService = mock()

    private fun controller(heartbeatInterval: Duration = Duration.ofMillis(10)) = MatchingController(
        gameMatchService,
        sessionLostReportService,
        MatchEventStreamProperties(heartbeatInterval = heartbeatInterval),
    )

    @Test
    fun `프록시 버퍼링을 끄는 헤더를 응답에 붙인다`() = runTest {
        whenever(gameMatchService.events(USER_ID)).thenReturn(emptyFlow())
        val response = MockServerHttpResponse()

        controller().events(USER_ID, response)

        assertThat(response.headers.getFirst("X-Accel-Buffering")).isEqualTo("no")
        assertThat(response.headers.cacheControl).isEqualTo("no-cache, no-transform")
    }

    /** The first frame has to leave before any ticket update, or the client waits on a silent socket. */
    @Test
    fun `구독 즉시 keep-alive를 보내고 주기적으로 반복한다`() = runTest {
        whenever(gameMatchService.events(USER_ID)).thenReturn(emptyFlow())

        val events = controller().events(USER_ID, MockServerHttpResponse()).take(2).toList()

        assertThat(events).allSatisfy {
            assertThat(it.comment()).isEqualTo("keep-alive")
            assertThat(it.data() as MatchTicketResponse?).isNull()
        }
    }

    @Test
    fun `keep-alive와 함께 티켓 갱신도 그대로 흘려보낸다`() = runTest {
        whenever(gameMatchService.events(USER_ID)).thenReturn(flowOf(ticket))

        val events = controller(heartbeatInterval = Duration.ofMinutes(1))
            .events(USER_ID, MockServerHttpResponse())
            .take(2)
            .toList()

        val update = events.single { it.data() != null }
        assertThat(update.data() as MatchTicketResponse?).isEqualTo(MatchTicketResponse.from(ticket))
        assertThat(update.event()).isEqualTo("match-ticket-updated")
        assertThat(update.id()).isEqualTo("7")
    }

    private companion object {
        const val USER_ID = 42L

        val ticket = MatchTicket(
            ticketId = "ticket-1",
            userId = USER_ID,
            mmr = 1000,
            state = MatchTicketState.QUEUED,
            version = 7,
            createdAt = Instant.parse("2026-08-14T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-14T00:00:00Z"),
        )
    }
}
