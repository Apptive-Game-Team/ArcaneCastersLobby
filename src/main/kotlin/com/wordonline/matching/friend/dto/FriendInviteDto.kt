package com.wordonline.matching.friend.dto

import com.wordonline.matching.friend.domain.FriendInvite
import com.wordonline.matching.friend.domain.FriendInviteStatus
import com.wordonline.matching.matching.dto.MatchedInfoDto
import java.time.Instant

data class FriendInviteDto(
    val inviteId: String,
    val inviterId: Long,
    val inviterName: String,
    val inviteeId: Long,
    val inviteeName: String,
    val status: FriendInviteStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
    val matchInfo: MatchedInfoDto? = null,
) {
    companion object {
        fun from(invite: FriendInvite) = FriendInviteDto(
            inviteId = invite.inviteId,
            inviterId = invite.inviterId,
            inviterName = invite.inviterName,
            inviteeId = invite.inviteeId,
            inviteeName = invite.inviteeName,
            status = invite.status,
            createdAt = invite.createdAt,
            expiresAt = invite.expiresAt,
            matchInfo = invite.matchInfo,
        )
    }
}
