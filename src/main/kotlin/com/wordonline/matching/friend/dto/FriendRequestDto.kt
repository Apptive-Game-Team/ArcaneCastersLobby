package com.wordonline.matching.friend.dto

import com.wordonline.matching.friend.domain.FriendRequest
import com.wordonline.matching.friend.domain.FriendRequestStatus
import java.time.Instant

data class FriendRequestDto(
    val id: Long,
    val senderId: Long,
    val senderName: String,
    val receiverId: Long,
    val receiverName: String,
    val status: FriendRequestStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(request: FriendRequest, senderName: String, receiverName: String) = FriendRequestDto(
            id = request.id ?: 0L,
            senderId = request.senderId,
            senderName = senderName,
            receiverId = request.receiverId,
            receiverName = receiverName,
            status = request.status,
            createdAt = request.createdAt,
        )
    }
}
