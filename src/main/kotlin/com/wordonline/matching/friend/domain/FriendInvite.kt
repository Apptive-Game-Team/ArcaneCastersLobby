package com.wordonline.matching.friend.domain

import com.wordonline.matching.matching.dto.MatchedInfoDto
import java.time.Instant

data class FriendInvite(
    val inviteId: String,
    val inviterId: Long,
    val inviterName: String,
    val inviteeId: Long,
    val inviteeName: String,
    val status: FriendInviteStatus = FriendInviteStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant,
    val matchInfo: MatchedInfoDto? = null,
)

enum class FriendInviteStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELED,
    EXPIRED
}
