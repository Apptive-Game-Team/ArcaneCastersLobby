package com.wordonline.matching.friend.dto

data class FriendSearchResultDto(
    val userId: Long,
    val name: String,
    val email: String,
    val isFriend: Boolean,
    val hasPendingRequest: Boolean,
)
