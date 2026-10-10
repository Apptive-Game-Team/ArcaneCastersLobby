package com.wordonline.matching.friend.service

import com.wordonline.matching.auth.domain.UserStatus
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.friend.domain.FriendEvent
import com.wordonline.matching.friend.domain.FriendEventType
import com.wordonline.matching.friend.domain.FriendRequest
import com.wordonline.matching.friend.domain.FriendRequestStatus
import com.wordonline.matching.friend.dto.FriendRequestDto
import com.wordonline.matching.friend.dto.FriendSearchResultDto
import com.wordonline.matching.friend.dto.FriendSummaryDto
import com.wordonline.matching.friend.repository.FriendRepository
import com.wordonline.matching.friend.repository.FriendRequestRepository
import com.wordonline.matching.matching.client.AccountClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class FriendService(
    private val friendRepository: FriendRepository,
    private val friendRequestRepository: FriendRequestRepository,
    private val accountClient: AccountClient,
    private val userService: UserService,
    private val friendEventService: FriendEventService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getFriends(userId: Long): List<FriendSummaryDto> {
        val friendIds = friendRepository.findFriendIdsByUserId(userId).toList()
        return friendIds.mapNotNull { friendId ->
            try {
                val member = accountClient.getMemberSuspend(friendId)
                val mmr = userService.getMmr(friendId).awaitSingleOrNull() ?: 0L
                val status = userService.getStatus(friendId).awaitSingleOrNull() ?: UserStatus.Online
                FriendSummaryDto(
                    userId = friendId,
                    name = member.displayName,
                    email = member.email,
                    mmr = mmr,
                    status = status,
                )
            } catch (e: Exception) {
                log.warn("Failed to load friend details for friendId={}", friendId, e)
                null
            }
        }
    }

    suspend fun searchUsers(userId: Long, query: String): List<FriendSearchResultDto> {
        if (query.isBlank()) return emptyList()
        val members = accountClient.searchMembersSuspend(query.trim(), limit = 20)
        return members.mapNotNull { member ->
            val memberId = member.id ?: return@mapNotNull null
            if (memberId == userId || memberId < 0) return@mapNotNull null
            val isFriend = friendRepository.existsByUserIdAndFriendId(userId, memberId)
            val pending = friendRequestRepository.findPendingBetween(userId, memberId) != null
            FriendSearchResultDto(
                userId = memberId,
                name = member.displayName,
                email = member.email,
                isFriend = isFriend,
                hasPendingRequest = pending,
            )
        }
    }

    suspend fun sendFriendRequest(senderId: Long, targetQuery: String): FriendRequestDto {
        val candidates = accountClient.searchMembersSuspend(targetQuery.trim(), limit = 5)
        val target = candidates.firstOrNull { it.id != null && it.id != senderId }
            ?: throw IllegalArgumentException("User not found for query: $targetQuery")

        val targetId = target.id ?: throw IllegalArgumentException("User has no ID")
        if (targetId == senderId) {
            throw IllegalArgumentException("Cannot send friend request to yourself")
        }

        if (friendRepository.existsByUserIdAndFriendId(senderId, targetId)) {
            throw IllegalStateException("Already friends with user: ${target.displayName}")
        }

        if (friendRequestRepository.findPendingBetween(senderId, targetId) != null) {
            throw IllegalStateException("A pending friend request already exists between users")
        }

        val request = friendRequestRepository.save(
            FriendRequest(
                senderId = senderId,
                receiverId = targetId,
                status = FriendRequestStatus.PENDING,
            )
        )

        val senderMember = accountClient.getMemberSuspend(senderId)
        val dto = FriendRequestDto.from(request, senderMember.displayName, target.displayName)

        friendEventService.publish(
            targetId,
            FriendEvent(
                type = FriendEventType.FRIEND_REQUEST_RECEIVED,
                friendRequest = dto,
                targetUserId = senderId,
            )
        )

        return dto
    }

    suspend fun acceptFriendRequest(userId: Long, requestId: Long): FriendRequestDto {
        val request = friendRequestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Friend request not found: $requestId")

        if (request.receiverId != userId) {
            throw IllegalStateException("You are not the receiver of this friend request")
        }

        if (request.status != FriendRequestStatus.PENDING) {
            throw IllegalStateException("Friend request is not pending: ${request.status}")
        }

        friendRequestRepository.updateStatus(requestId, FriendRequestStatus.ACCEPTED.name)
        friendRepository.insertFriendship(request.senderId, request.receiverId)
        friendRepository.insertFriendship(request.receiverId, request.senderId)

        val senderMember = accountClient.getMemberSuspend(request.senderId)
        val receiverMember = accountClient.getMemberSuspend(request.receiverId)
        val dto = FriendRequestDto.from(
            request.copy(status = FriendRequestStatus.ACCEPTED),
            senderMember.displayName,
            receiverMember.displayName,
        )

        val event = FriendEvent(
            type = FriendEventType.FRIEND_REQUEST_ACCEPTED,
            friendRequest = dto,
            targetUserId = userId,
        )
        friendEventService.publish(request.senderId, event)
        friendEventService.publish(request.receiverId, event)

        return dto
    }

    suspend fun rejectFriendRequest(userId: Long, requestId: Long): FriendRequestDto {
        val request = friendRequestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Friend request not found: $requestId")

        if (request.receiverId != userId) {
            throw IllegalStateException("You are not the receiver of this friend request")
        }

        if (request.status != FriendRequestStatus.PENDING) {
            throw IllegalStateException("Friend request is not pending: ${request.status}")
        }

        friendRequestRepository.updateStatus(requestId, FriendRequestStatus.REJECTED.name)

        val senderMember = accountClient.getMemberSuspend(request.senderId)
        val receiverMember = accountClient.getMemberSuspend(request.receiverId)
        val dto = FriendRequestDto.from(
            request.copy(status = FriendRequestStatus.REJECTED),
            senderMember.displayName,
            receiverMember.displayName,
        )

        friendEventService.publish(
            request.senderId,
            FriendEvent(
                type = FriendEventType.FRIEND_REQUEST_REJECTED,
                friendRequest = dto,
                targetUserId = userId,
            )
        )

        return dto
    }

    suspend fun cancelFriendRequest(userId: Long, requestId: Long): FriendRequestDto {
        val request = friendRequestRepository.findById(requestId)
            ?: throw IllegalArgumentException("Friend request not found: $requestId")

        if (request.senderId != userId) {
            throw IllegalStateException("You are not the sender of this friend request")
        }

        if (request.status != FriendRequestStatus.PENDING) {
            throw IllegalStateException("Friend request is not pending: ${request.status}")
        }

        friendRequestRepository.updateStatus(requestId, FriendRequestStatus.CANCELED.name)

        val senderMember = accountClient.getMemberSuspend(request.senderId)
        val receiverMember = accountClient.getMemberSuspend(request.receiverId)
        return FriendRequestDto.from(
            request.copy(status = FriendRequestStatus.CANCELED),
            senderMember.displayName,
            receiverMember.displayName,
        )
    }

    suspend fun removeFriend(userId: Long, friendId: Long) {
        friendRepository.deleteFriendship(userId, friendId)

        friendEventService.publish(
            friendId,
            FriendEvent(
                type = FriendEventType.FRIEND_REMOVED,
                targetUserId = userId,
            )
        )
    }

    suspend fun getReceivedRequests(userId: Long): List<FriendRequestDto> {
        val requests = friendRequestRepository.findPendingByReceiverId(userId).toList()
        return requests.map { req ->
            val sender = accountClient.getMemberSuspend(req.senderId)
            val receiver = accountClient.getMemberSuspend(req.receiverId)
            FriendRequestDto.from(req, sender.displayName, receiver.displayName)
        }
    }

    suspend fun getSentRequests(userId: Long): List<FriendRequestDto> {
        val requests = friendRequestRepository.findPendingBySenderId(userId).toList()
        return requests.map { req ->
            val sender = accountClient.getMemberSuspend(req.senderId)
            val receiver = accountClient.getMemberSuspend(req.receiverId)
            FriendRequestDto.from(req, sender.displayName, receiver.displayName)
        }
    }
}
