package com.wordonline.matching.friend.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.friend.domain.FriendEvent
import com.wordonline.matching.friend.dto.FriendInviteDto
import com.wordonline.matching.friend.dto.FriendRequestDto
import com.wordonline.matching.friend.dto.FriendSearchResultDto
import com.wordonline.matching.friend.dto.FriendSummaryDto
import com.wordonline.matching.friend.dto.SendFriendRequestDto
import com.wordonline.matching.friend.service.FriendEventService
import com.wordonline.matching.friend.service.FriendInviteService
import com.wordonline.matching.friend.service.FriendService
import com.wordonline.matching.matching.dto.MatchedInfoDto
import kotlinx.coroutines.flow.Flow
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/friends")
class FriendController(
    private val friendService: FriendService,
    private val friendInviteService: FriendInviteService,
    private val friendEventService: FriendEventService,
) {

    @GetMapping
    suspend fun getFriends(@UserId userId: Long?): List<FriendSummaryDto> {
        return friendService.getFriends(userId!!)
    }

    @DeleteMapping("/{friendId}")
    suspend fun removeFriend(
        @UserId userId: Long?,
        @PathVariable friendId: Long,
    ) {
        friendService.removeFriend(userId!!, friendId)
    }

    @GetMapping("/search")
    suspend fun searchUsers(
        @UserId userId: Long?,
        @RequestParam query: String,
    ): List<FriendSearchResultDto> {
        return friendService.searchUsers(userId!!, query)
    }

    @GetMapping("/requests/received")
    suspend fun getReceivedRequests(@UserId userId: Long?): List<FriendRequestDto> {
        return friendService.getReceivedRequests(userId!!)
    }

    @GetMapping("/requests/sent")
    suspend fun getSentRequests(@UserId userId: Long?): List<FriendRequestDto> {
        return friendService.getSentRequests(userId!!)
    }

    @PostMapping("/requests")
    suspend fun sendFriendRequest(
        @UserId userId: Long?,
        @RequestBody body: SendFriendRequestDto,
    ): FriendRequestDto {
        return friendService.sendFriendRequest(userId!!, body.targetQuery)
    }

    @PostMapping("/requests/{requestId}/accept")
    suspend fun acceptFriendRequest(
        @UserId userId: Long?,
        @PathVariable requestId: Long,
    ): FriendRequestDto {
        return friendService.acceptFriendRequest(userId!!, requestId)
    }

    @PostMapping("/requests/{requestId}/reject")
    suspend fun rejectFriendRequest(
        @UserId userId: Long?,
        @PathVariable requestId: Long,
    ): FriendRequestDto {
        return friendService.rejectFriendRequest(userId!!, requestId)
    }

    @DeleteMapping("/requests/{requestId}")
    suspend fun cancelFriendRequest(
        @UserId userId: Long?,
        @PathVariable requestId: Long,
    ): FriendRequestDto {
        return friendService.cancelFriendRequest(userId!!, requestId)
    }

    @PostMapping("/{friendId}/invite")
    suspend fun inviteFriend(
        @UserId userId: Long?,
        @PathVariable friendId: Long,
    ): FriendInviteDto {
        return friendInviteService.invite(userId!!, friendId)
    }

    @PostMapping("/invites/{inviteId}/accept")
    suspend fun acceptInvite(
        @UserId userId: Long?,
        @PathVariable inviteId: String,
    ): MatchedInfoDto {
        return friendInviteService.acceptInvite(userId!!, inviteId)
    }

    @PostMapping("/invites/{inviteId}/reject")
    suspend fun rejectInvite(
        @UserId userId: Long?,
        @PathVariable inviteId: String,
    ): FriendInviteDto {
        return friendInviteService.rejectInvite(userId!!, inviteId)
    }

    @DeleteMapping("/invites/{inviteId}")
    suspend fun cancelInvite(
        @UserId userId: Long?,
        @PathVariable inviteId: String,
    ): FriendInviteDto {
        return friendInviteService.cancelInvite(userId!!, inviteId)
    }

    @GetMapping("/invites/pending")
    fun getPendingInvites(@UserId userId: Long?): List<FriendInviteDto> {
        return friendInviteService.getPendingInvites(userId!!)
    }

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @UserId userId: Long?,
        response: ServerHttpResponse,
    ): Flow<ServerSentEvent<FriendEvent>> {
        response.headers.set("X-Accel-Buffering", "no")
        response.headers.cacheControl = "no-cache, no-transform"
        return friendEventService.events(userId!!)
    }
}
