package com.wordonline.matching.matching.repository

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchedInfoDto
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Duration
import java.time.Instant

/**
 * Redis-side behaviour of the ticket state machine, executed against the real Lua.
 */
class MatchTicketRepositoryScriptTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()
    private val properties = MatchTicketProperties(terminalTtl = Duration.ofMinutes(30))
    private val sandbox = RedisScriptSandbox()
    private val repository = sandboxMatchTicketRepository(sandbox, properties)

    private val matchedAt = Instant.parse("2026-08-10T00:00:00Z")

    private fun matchInfo(sessionId: String = "session-1") = MatchedInfoDto(
        message = "Successfully Matched",
        server = "http://localhost:7777",
        leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
        rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
        sessionId = sessionId,
        webSocketUrl = "ws://localhost:7777/ws",
    )

    private fun ticket(
        ticketId: String,
        userId: Long,
        state: MatchTicketState,
        sessionId: String = "session-1",
    ) = MatchTicket(
        ticketId = ticketId,
        userId = userId,
        mmr = 1000L,
        state = state,
        version = 1L,
        matchInfo = if (state == MatchTicketState.MATCHED) matchInfo(sessionId) else null,
        serverId = 7L,
        serverInstanceId = "instance-a",
        createdAt = matchedAt,
        updatedAt = matchedAt,
    )

    private fun seed(ticket: MatchTicket) {
        sandbox.set("matching:ticket:${ticket.ticketId}", objectMapper.writeValueAsString(ticket))
        sandbox.set("matching:active:${ticket.userId}", ticket.ticketId)
        if (ticket.state == MatchTicketState.MATCHED) {
            sandbox.zadd(MatchTicketRepository.MATCHED_KEY, ticket.ticketId, ticket.updatedAt.toEpochMilli().toDouble())
        }
    }

    @Test
    fun `단일 claim 은 QUEUED 티켓을 한 번만 ALLOCATING 으로 옮기고 lease 를 건다`() = runTest {
        val queued = ticket("ticket-1", 1L, MatchTicketState.QUEUED)
        seed(queued)
        sandbox.zadd("matching:queue", "1", 1.0)
        val claiming = queued.copy(
            state = MatchTicketState.ALLOCATING,
            version = 2L,
            allocationLeaseUntil = matchedAt.plusSeconds(30),
        )

        assertThat(repository.claim(claiming)).isTrue()
        assertThat(sandbox.zmembers("matching:queue")).isEmpty()
        assertThat(sandbox.zscore("matching:allocation-leases", "ticket-1"))
            .isEqualTo(matchedAt.plusSeconds(30).toEpochMilli().toDouble())
        assertThat(repository.getActive(1L)?.state).isEqualTo(MatchTicketState.ALLOCATING)

        assertThat(repository.claim(claiming)).`as`("이미 ALLOCATING 인 티켓은 다시 claim 되면 안 된다").isFalse()
    }

    @Test
    fun `MATCHED 전이는 티켓에 만료를 걸지 않는다`() = runTest {
        val first = ticket("ticket-1", 1L, MatchTicketState.ALLOCATING)
        val second = ticket("ticket-2", 2L, MatchTicketState.ALLOCATING)
        seed(first)
        seed(second)

        val completed = repository.transitionPair(
            first.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            second.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            MatchTicketState.ALLOCATING,
        )

        assertThat(completed).isTrue()
        assertThat(sandbox.ttlMillis("matching:ticket:ticket-1"))
            .`as`("30분을 넘는 세션의 티켓이 조용히 사라지면 안 된다")
            .isNull()
        assertThat(sandbox.ttlMillis("matching:ticket:ticket-2")).isNull()
    }

    @Test
    fun `MATCHED 전이는 active 포인터를 유지한다`() = runTest {
        val first = ticket("ticket-1", 1L, MatchTicketState.ALLOCATING)
        val second = ticket("ticket-2", 2L, MatchTicketState.ALLOCATING)
        seed(first)
        seed(second)

        repository.transitionPair(
            first.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            second.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            MatchTicketState.ALLOCATING,
        )

        assertThat(sandbox.get("matching:active:1")).isEqualTo("ticket-1")
        assertThat(sandbox.get("matching:active:2")).isEqualTo("ticket-2")
    }

    @Test
    fun `MATCHED 전이는 matched 인덱스에 티켓을 등록한다`() = runTest {
        val first = ticket("ticket-1", 1L, MatchTicketState.ALLOCATING)
        val second = ticket("ticket-2", 2L, MatchTicketState.ALLOCATING)
        seed(first)
        seed(second)

        repository.transitionPair(
            first.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            second.copy(state = MatchTicketState.MATCHED, version = 2L, matchInfo = matchInfo()),
            MatchTicketState.ALLOCATING,
        )

        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY))
            .containsExactlyInAnyOrder("ticket-1", "ticket-2")
    }

    @Test
    fun `terminal 쌍 전이는 만료를 걸고 두 active 포인터를 모두 해제한다`() = runTest {
        val first = ticket("ticket-1", 1L, MatchTicketState.MATCHED)
        val second = ticket("ticket-2", 2L, MatchTicketState.MATCHED)
        seed(first)
        seed(second)

        val completed = repository.transitionPair(
            first.copy(state = MatchTicketState.FAILED, version = 2L, reason = "SESSION_LOST"),
            second.copy(state = MatchTicketState.FAILED, version = 2L, reason = "SESSION_LOST"),
            MatchTicketState.MATCHED,
        )

        assertThat(completed).isTrue()
        assertThat(sandbox.ttlMillis("matching:ticket:ticket-1")).isEqualTo(Duration.ofMinutes(30).toMillis())
        assertThat(sandbox.get("matching:active:1")).isNull()
        assertThat(sandbox.get("matching:active:2")).isNull()
        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY)).isEmpty()
    }

    @Test
    fun `terminal 단일 전이는 자기 티켓을 가리킬 때만 active 포인터를 지운다`() = runTest {
        val stale = ticket("ticket-old", 1L, MatchTicketState.MATCHED)
        seed(stale)
        // the user already re-queued, so the pointer names a newer ticket
        sandbox.set("matching:active:1", "ticket-new")

        repository.transition(
            stale.copy(state = MatchTicketState.FAILED, version = 2L, reason = "SESSION_LOST"),
            MatchTicketState.MATCHED,
        )

        assertThat(sandbox.get("matching:active:1"))
            .`as`("이미 새 티켓을 들고 있으면 포인터를 건드리지 않는다")
            .isEqualTo("ticket-new")
    }

    @Test
    fun `terminal 단일 전이는 active 포인터를 해제하고 만료를 건다`() = runTest {
        val matched = ticket("ticket-1", 1L, MatchTicketState.MATCHED)
        seed(matched)

        val released = repository.transition(
            matched.copy(state = MatchTicketState.FAILED, version = 2L, reason = "SESSION_LOST"),
            MatchTicketState.MATCHED,
        )

        assertThat(released?.state).isEqualTo(MatchTicketState.FAILED)
        assertThat(sandbox.get("matching:active:1")).isNull()
        assertThat(sandbox.ttlMillis("matching:ticket:ticket-1")).isEqualTo(Duration.ofMinutes(30).toMillis())
        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY)).isEmpty()
    }

    @Test
    fun `QUEUED로 복구되는 전이에는 만료가 남지 않는다`() = runTest {
        val allocating = ticket("ticket-1", 1L, MatchTicketState.ALLOCATING)
        seed(allocating)

        repository.transition(
            allocating.copy(state = MatchTicketState.QUEUED, version = 2L, reason = "ALLOCATION_LEASE_EXPIRED"),
            MatchTicketState.ALLOCATING,
        )

        assertThat(sandbox.ttlMillis("matching:ticket:ticket-1")).isNull()
        assertThat(sandbox.get("matching:active:1")).isEqualTo("ticket-1")
        assertThat(sandbox.zmembers("matching:queue")).containsExactly("1")
    }

    @Test
    fun `취소된 티켓은 active 포인터를 남기지 않는다`() = runTest {
        val queued = ticket("ticket-1", 1L, MatchTicketState.QUEUED)
        seed(queued)
        sandbox.zadd("matching:queue", "1", matchedAt.toEpochMilli().toDouble())

        val canceled = repository.cancel(1L, "ticket-1")

        assertThat(canceled?.state).isEqualTo(MatchTicketState.CANCELED)
        assertThat(sandbox.get("matching:active:1"))
            .`as`("티켓 키만 만료되면 dangling 포인터가 남는다")
            .isNull()
        assertThat(sandbox.ttlMillis("matching:ticket:ticket-1")).isEqualTo(Duration.ofMinutes(30).toMillis())
    }

    @Test
    fun `settledMatched는 유예 시간이 지난 MATCHED 티켓만 돌려준다`() = runTest {
        val fresh = ticket("ticket-fresh", 1L, MatchTicketState.MATCHED)
        val old = ticket("ticket-old", 2L, MatchTicketState.MATCHED, sessionId = "session-2")
        sandbox.set("matching:ticket:ticket-fresh", objectMapper.writeValueAsString(fresh))
        sandbox.set("matching:ticket:ticket-old", objectMapper.writeValueAsString(old))
        sandbox.zadd(MatchTicketRepository.MATCHED_KEY, "ticket-fresh", matchedAt.toEpochMilli().toDouble())
        sandbox.zadd(MatchTicketRepository.MATCHED_KEY, "ticket-old", matchedAt.minusSeconds(600).toEpochMilli().toDouble())

        val settled = repository.settledMatched(matchedAt.minusSeconds(60), 10)

        assertThat(settled.map { it.ticketId }).containsExactly("ticket-old")
    }

    @Test
    fun `settledMatched는 사라진 티켓의 인덱스 항목을 정리한다`() = runTest {
        sandbox.zadd(MatchTicketRepository.MATCHED_KEY, "ticket-gone", matchedAt.toEpochMilli().toDouble())

        val settled = repository.settledMatched(matchedAt.plusSeconds(1), 10)

        assertThat(settled).isEmpty()
        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY)).isEmpty()
    }
}
