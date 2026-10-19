package com.wordonline.matching.friend.dto

import com.wordonline.matching.auth.domain.UserStatus

data class FriendSummaryDto(
    val userId: Long,
    val name: String,
    val email: String,
    val mmr: Long,
    val status: UserStatus,
)
