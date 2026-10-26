package com.wordonline.matching.friend.dto

data class FriendSummaryDto(
    val userId: Long,
    val name: String,
    val email: String,
    val mmr: Long,
    val status: String,
)
