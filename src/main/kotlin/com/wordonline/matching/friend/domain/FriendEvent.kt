package com.wordonline.matching.friend.domain

import com.wordonline.matching.friend.dto.FriendInviteDto
import com.wordonline.matching.friend.dto.FriendRequestDto
import com.wordonline.matching.matching.dto.MatchedInfoDto

enum class FriendEventType {
    FRIEND_REQUEST_RECEIVED,
    FRIEND_REQUEST_ACCEPTED,
    FRIEND_REQUEST_REJECTED,
    FRIEND_REMOVED,
    FRIEND_INVITE_RECEIVED,
    FRIEND_INVITE_ACCEPTED,
    FRIEND_INVITE_REJECTED,
    FRIEND_INVITE_CANCELED,
    FRIEND_MATCH_READY,
    FRIEND_STATUS_CHANGED,
}

data class FriendEvent(
    val type: FriendEventType,
    val friendRequest: FriendRequestDto? = null,
    val invite: FriendInviteDto? = null,
    val matchInfo: MatchedInfoDto? = null,
    val targetUserId: Long? = null,
    val message: String? = null,
)
