package com.wordonline.matching.friend.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("friend_requests")
data class FriendRequest(
    @Id val id: Long? = null,
    val senderId: Long,
    val receiverId: Long,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELED
}
