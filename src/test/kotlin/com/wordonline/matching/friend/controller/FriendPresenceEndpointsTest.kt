package com.wordonline.matching.friend.controller

import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.friend.domain.FriendInviteStatus
import com.wordonline.matching.friend.dto.FriendInviteDto
import com.wordonline.matching.friend.service.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.*
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

@WebFluxTest(controllers = [FriendController::class])
@Import(UserIdResolver::class)
class FriendPresenceEndpointsTest {
    @Autowired private lateinit var client: WebTestClient
    @MockitoBean private lateinit var friends: FriendService
    @MockitoBean private lateinit var invites: FriendInviteService
    @MockitoBean private lateinit var events: FriendEventService
    @MockitoBean private lateinit var presence: FriendPresenceService

    private fun authenticated() = client.mutateWith(mockJwt().jwt { it.claim("memberId", 1L) }).mutateWith(csrf())
    private fun invite() = FriendInviteDto("fixture", 1, "sender", 2, "recipient",
        FriendInviteStatus.PENDING, Instant.EPOCH, Instant.EPOCH.plusSeconds(30),
        deliveredAt = Instant.EPOCH.plusSeconds(1), revision = 2)

    @Test
    fun `보낸 초대 조회는 수신 시각과 revision을 반환한다`() {
        whenever(invites.getSentInvites(1)).thenReturn(listOf(invite()))
        authenticated().get().uri("/api/friends/invites/sent").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$[0].revision").isEqualTo(2)
            .jsonPath("$[0].deliveredAt").isEqualTo("1970-01-01T00:00:01Z")
        verify(invites).getSentInvites(1)
    }

    @Test
    fun `초대 수신 확인은 인증 사용자와 초대 ID를 전달한다`() {
        whenever(invites.acknowledgeInvite(1, "fixture")).thenReturn(invite())
        authenticated().post().uri("/api/friends/invites/fixture/received").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.revision").isEqualTo(2)
        verify(invites).acknowledgeInvite(1, "fixture")
    }

    @Test
    fun `heartbeat는 인증 사용자의 상태를 갱신한다`(): Unit = runBlocking {
        authenticated().post().uri("/api/friends/presence").exchange().expectStatus().isOk
        verify(presence).heartbeat(1)
    }
}
