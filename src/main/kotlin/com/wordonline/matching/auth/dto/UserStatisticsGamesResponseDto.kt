package com.wordonline.matching.auth.dto

data class UserStatisticsGamesResponseDto(
    val games: List<UserGameRecordResponseDto>,
    val page: Int,
    val size: Int,
    val totalPages: Int,
    val last: Boolean,
)
