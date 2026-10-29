package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.domain.SessionLiveness
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.server.exception.GameServerUnreachableException
import com.wordonline.matching.server.service.ServerHealthRegistry
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * A finished game and a lost one need the same cleanup but are not the same event, and the
 * only thing that tells them apart is the host's boot generation. These tests pin which
 * combination of signals yields which verdict, because getting it wrong either buries every
 * restart in the noise of ordinary match endings or reports an incident on every game.
 */
class SessionLivenessProbeTest {

    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val serverHealthRegistry = ServerHealthRegistry(GameServerProperties())
    private val probe = SessionLivenessProbe(legacyGameMatchService, serverHealthRegistry)

    private val host = "http://localhost:7777"

    init {
        serverHealthRegistry.replaceServers(
            listOf(
                Server(
                    id = 7L,
                    protocol = "http",
                    domain = "localhost",
                    port = 7777,
                    state = ServerState.ACTIVE,
                    type = ServerType.GAME,
                    instanceId = "boot-1",
                ),
            ),
        )
    }

    private fun ticket(instanceId: String?) = MatchTicket(
        ticketId = "ticket-1",
        userId = 1L,
        mmr = 1000L,
        state = MatchTicketState.MATCHED,
        version = 2L,
        matchInfo = MatchedInfoDto(
            message = "Successfully Matched",
            server = host,
            leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
            rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
            sessionId = "session-1",
            webSocketUrl = "ws://localhost:7777/ws",
        ),
        serverId = 7L,
        serverInstanceId = instanceId,
        createdAt = Instant.parse("2026-08-10T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-10T00:00:00Z"),
    )

    @Test
    fun `세대값이 다르면 호스트에 묻지 않고 소실로 판정한다`() = runTest {
        val liveness = probe.check(ticket(instanceId = "boot-0"))

        assertThat(liveness).isEqualTo(SessionLiveness.LOST)
        verify(legacyGameMatchService, never()).isSessionActive(any(), any())
    }

    @Test
    fun `세대값이 같고 세션이 비활성이면 정상 종료로 판정한다`() = runTest {
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        val liveness = probe.check(ticket(instanceId = "boot-1"))

        assertThat(liveness)
            .`as`("프로세스가 그대로면 세션이 없어진 이유는 게임이 끝났기 때문이다")
            .isEqualTo(SessionLiveness.ENDED)
    }

    @Test
    fun `세대값이 같고 세션이 살아있으면 ALIVE다`() = runTest {
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(true)

        assertThat(probe.check(ticket(instanceId = "boot-1"))).isEqualTo(SessionLiveness.ALIVE)
    }

    @Test
    fun `세대값을 모르면 정상 종료로 단정하지 않는다`() = runTest {
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        val liveness = probe.check(ticket(instanceId = null))

        assertThat(liveness)
            .`as`("구버전 게임 서버의 NULL은 같은 프로세스라는 증거가 아니다")
            .isEqualTo(SessionLiveness.LOST)
    }

    @Test
    fun `호스트가 응답하지 않으면 UNKNOWN이다`() = runTest {
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))

        assertThat(probe.check(ticket(instanceId = "boot-1"))).isEqualTo(SessionLiveness.UNKNOWN)
    }
    @Test
    fun `lockstep은 세대가 달라도 호스트의 복구 세션이 활성 상태면 유지한다`() = runTest {
        val original = ticket(instanceId = "boot-0")
        val recovering = original.copy(matchInfo = original.matchInfo!!.copy(lockstepSimulationVersion = "lockstep-v1"))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1", "lockstep-v1")).thenReturn(true)
        assertThat(probe.check(recovering)).isEqualTo(SessionLiveness.ALIVE)
        verify(legacyGameMatchService).isSessionActive(host, "session-1", "lockstep-v1")
    }

    @Test
    fun `lockstep 재시작 복구 조회 실패는 소실로 확정하지 않는다`() = runTest {
        val original = ticket(instanceId = "boot-0")
        val recovering = original.copy(matchInfo = original.matchInfo!!.copy(lockstepSimulationVersion = "lockstep-v1"))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1", "lockstep-v1"))
            .thenThrow(GameServerUnreachableException("recovery pending"))
        assertThat(probe.check(recovering)).isEqualTo(SessionLiveness.UNKNOWN)
    }

}
