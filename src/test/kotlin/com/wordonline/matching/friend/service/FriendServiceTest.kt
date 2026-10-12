package com.wordonline.matching.friend.service

import com.wordonline.matching.auth.domain.UserStatus
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.friend.domain.FriendRequest
import com.wordonline.matching.friend.domain.FriendRequestStatus
import com.wordonline.matching.friend.repository.FriendRepository
import com.wordonline.matching.friend.repository.FriendRequestRepository
import com.wordonline.matching.matching.client.AccountClient
import com.wordonline.matching.matching.dto.AccountMemberResponseDto
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono
import java.time.Instant

class FriendServiceTest {

    private val friendRepository: FriendRepository = mock()
    private val friendRequestRepository: FriendRequestRepository = mock()
    private val accountClient: AccountClient = mock()
    private val userService: UserService = mock()
    private val friendEventService: FriendEventService = mock()

    private val friendService = FriendService(
        friendRepository = friendRepository,
        friendRequestRepository = friendRequestRepository,
        accountClient = accountClient,
        userService = userService,
        friendEventService = friendEventService,
    )

    @Test
    @DisplayName("친구 목록을 조회하면 회원 정보와 접속 상태를 합쳐서 반환한다")
    fun getFriends_returnsFriendSummaries() = runTest {
        val userId = 1L
        val friendId = 2L

        whenever(friendRepository.findFriendIdsByUserId(userId)).thenReturn(listOf(friendId).asFlow())
        whenever(accountClient.getMemberSuspend(friendId)).thenReturn(
            AccountMemberResponseDto(email = "friend@example.com", name = "FriendUser", id = friendId)
        )
        whenever(userService.getMmr(friendId)).thenReturn(Mono.just(1200L))
        whenever(userService.getStatus(friendId)).thenReturn(Mono.just(UserStatus.Online))

        val result = friendService.getFriends(userId)

        assertThat(result).hasSize(1)
        val friend = result.first()
        assertThat(friend.userId).isEqualTo(friendId)
        assertThat(friend.name).isEqualTo("FriendUser")
        assertThat(friend.mmr).isEqualTo(1200L)
        assertThat(friend.status).isEqualTo(UserStatus.Online)
    }

    @Test
    @DisplayName("자신에게 친구 요청을 보내면 예외가 발생한다")
    fun sendFriendRequest_toSelf_throwsException() = runTest {
        val userId = 1L
        whenever(accountClient.searchMembersSuspend("SelfUser", 5)).thenReturn(
            listOf(AccountMemberResponseDto(email = "self@example.com", name = "SelfUser", id = userId))
        )

        assertThrows<IllegalArgumentException> {
            friendService.sendFriendRequest(userId, "SelfUser")
        }
    }

    @Test
    @DisplayName("친구 요청을 수락하면 양방향 관계가 저장되고 ACCEPTED 상태를 반환한다")
    fun acceptFriendRequest_success() = runTest {
        val receiverId = 2L
        val senderId = 1L
        val requestId = 100L

        val request = FriendRequest(
            id = requestId,
            senderId = senderId,
            receiverId = receiverId,
            status = FriendRequestStatus.PENDING,
            createdAt = Instant.now(),
        )

        whenever(friendRequestRepository.findById(requestId)).thenReturn(request)
        whenever(friendRequestRepository.updateStatus(requestId, FriendRequestStatus.ACCEPTED.name)).thenReturn(1L)
        whenever(friendRepository.insertFriendship(senderId, receiverId)).thenReturn(1L)
        whenever(friendRepository.insertFriendship(receiverId, senderId)).thenReturn(1L)
        whenever(accountClient.getMemberSuspend(senderId)).thenReturn(
            AccountMemberResponseDto(email = "sender@example.com", name = "Sender", id = senderId)
        )
        whenever(accountClient.getMemberSuspend(receiverId)).thenReturn(
            AccountMemberResponseDto(email = "receiver@example.com", name = "Receiver", id = receiverId)
        )

        val result = friendService.acceptFriendRequest(receiverId, requestId)

        assertThat(result.status).isEqualTo(FriendRequestStatus.ACCEPTED)
        verify(friendRepository).insertFriendship(senderId, receiverId)
        verify(friendRepository).insertFriendship(receiverId, senderId)
        org.mockito.kotlin.verify(friendEventService, org.mockito.kotlin.times(2)).publish(any(), any())
    }
}
