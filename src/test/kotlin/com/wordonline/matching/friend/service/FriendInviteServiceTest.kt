package com.wordonline.matching.friend.service

import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.friend.domain.FriendInvite
import com.wordonline.matching.friend.domain.FriendEventType
import java.time.Instant
import com.wordonline.matching.friend.domain.FriendInviteStatus
import com.wordonline.matching.friend.repository.FriendRepository
import com.wordonline.matching.matching.client.AccountClient
import com.wordonline.matching.matching.dto.AccountMemberResponseDto
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.session.domain.SessionPlacement
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.*
import reactor.core.publisher.Mono

class FriendInviteServiceTest {
    private val friends: FriendRepository = mock()
    private val accounts: AccountClient = mock()
    private val decks: DeckService = mock()
    private val queue: MatchingQueueRepository = mock()
    private val matches: LegacyGameMatchService = mock()
    private val events: FriendEventService = mock()
    private val presence: FriendPresenceService = mock()
    private val service = FriendInviteService(friends, accounts, decks, queue, matches, events, presence)

    private suspend fun createInvite(): String {
        whenever(friends.existsByUserIdAndFriendId(1, 2)).thenReturn(true)
        whenever(presence.status(any())).thenReturn("Online")
        whenever(decks.hasValidSelectedDeck(any())).thenReturn(Mono.just(true))
        whenever(accounts.getMemberSuspend(any())).thenReturn(AccountMemberResponseDto("user@example.com", "user"))
        return service.invite(1, 2).inviteId
    }

    @Test
    fun `생성만으로 전달 완료가 되지 않고 수신 확인은 멱등적이다`(): Unit = runTest {
        val id = createInvite()
        assertThat(service.getSentInvites(1).single().deliveredAt).isNull()
        assertThrows<IllegalStateException> { service.acknowledgeInvite(1, id) }
        val delivered = service.acknowledgeInvite(2, id)
        assertThat(delivered.deliveredAt).isNotNull()
        assertThat(delivered.revision).isEqualTo(2)
        assertThat(service.acknowledgeInvite(2, id)).isEqualTo(delivered)
        assertThat(service.getSentInvites(3)).isEmpty()
    }

    @Test
    fun `취소한 초대의 늦은 수신 확인은 종료 상태를 바꾸지 않는다`(): Unit = runTest {
        val id = createInvite()
        val canceled = service.cancelInvite(1, id)
        assertThat(service.acknowledgeInvite(2, id)).isEqualTo(canceled)
        assertThat(service.getPendingInvites(2)).isEmpty()
        assertThrows<IllegalStateException> { service.rejectInvite(2, id) }
    }

    @Test
    fun `오프라인 친구에게 새 초대를 만들지 않는다`(): Unit = runTest {
        createInvite()
        whenever(presence.status(2)).thenReturn("Offline")
        assertThrows<IllegalStateException> { service.invite(1, 2) }
    }

    @Test
    fun `방 생성 중 수신 확인이 도착해도 수락 결과에 수신 시각을 보존한다`(): Unit = runTest {
        val id = createInvite()
        val info: MatchedInfoDto = mock()
        whenever(queue.nextSessionId()).thenReturn(Mono.just(1))
        whenever(matches.createSession(any(), any(), any())).thenAnswer {
            service.acknowledgeInvite(2, id)
            SessionPlacement(info, 1, "instance")
        }
        service.acceptInvite(2, id)
        val accepted = service.getSentInvites(1).single()
        assertThat(accepted.status).isEqualTo(FriendInviteStatus.ACCEPTED)
        assertThat(accepted.deliveredAt).isNotNull()
        assertThat(accepted.revision).isEqualTo(3)
        assertThrows<IllegalStateException> { service.cancelInvite(1, id) }
    }

    private suspend fun expiredInvite(): String {
        val id = createInvite()
        val field = FriendInviteService::class.java.getDeclaredField("invites")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stored = field.get(service) as MutableMap<String, FriendInvite>
        stored[id] = stored.getValue(id).copy(expiresAt = Instant.now().minusSeconds(1))
        clearInvocations(events)
        return id
    }

    @Test
    fun `만료 초대 취소는 양쪽에 만료 이벤트만 보낸다`(): Unit = runTest {
        val id = expiredInvite()
        val result = service.cancelInvite(1, id)
        assertThat(result.status).isEqualTo(FriendInviteStatus.EXPIRED)
        verify(events).publish(eq(1L), argThat { type == FriendEventType.FRIEND_INVITE_EXPIRED && invite?.inviteId == id })
        verify(events).publish(eq(2L), argThat { type == FriendEventType.FRIEND_INVITE_EXPIRED && invite?.inviteId == id })
        service.getSentInvites(1)
        verifyNoMoreInteractions(events)
    }

    @Test
    fun `만료 초대 거절은 양쪽에 만료 이벤트만 보낸다`(): Unit = runTest {
        val id = expiredInvite()
        val result = service.rejectInvite(2, id)
        assertThat(result.status).isEqualTo(FriendInviteStatus.EXPIRED)
        verify(events).publish(eq(1L), argThat { type == FriendEventType.FRIEND_INVITE_EXPIRED && invite?.inviteId == id })
        verify(events).publish(eq(2L), argThat { type == FriendEventType.FRIEND_INVITE_EXPIRED && invite?.inviteId == id })
        service.getPendingInvites(2)
        verifyNoMoreInteractions(events)
    }
}
