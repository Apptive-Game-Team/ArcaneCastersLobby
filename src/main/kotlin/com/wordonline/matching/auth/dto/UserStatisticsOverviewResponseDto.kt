package com.wordonline.matching.auth.dto

data class UserStatisticsOverviewResponseDto(
    val totalGameNum: Long,
    val totalWinNum: Long,
    val totalLoseNum: Long,
    val totalDrawNum: Long,
)
