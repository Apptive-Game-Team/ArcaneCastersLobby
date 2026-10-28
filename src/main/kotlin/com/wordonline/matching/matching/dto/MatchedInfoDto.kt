package com.wordonline.matching.matching.dto

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.session.domain.SessionRecoveryInfo

data class MatchedInfoDto @JvmOverloads constructor(
    val message: String,
    val server: String,
    val leftUser: UserDetailResponseDto,
    val rightUser: UserDetailResponseDto,
    val sessionId: String,
    val webSocketUrl: String? = null,
    val type: String = "matchedInfoDto",
    val lockstepSimulationVersion: String? = null,
    val mapType: String? = null,
) {
    constructor(
        sessionInfo: SessionRecoveryInfo,
        leftUser: UserDetailResponseDto,
        rightUser: UserDetailResponseDto,
    ) : this(
        message = "Successfully Match Info Recovered",
        server = sessionInfo.serverUrl(),
        leftUser = leftUser,
        rightUser = rightUser,
        sessionId = sessionInfo.sessionId(),
        webSocketUrl = sessionInfo.webSocketUrl(),
        lockstepSimulationVersion = sessionInfo.lockstepSimulationVersion(),
        mapType = sessionInfo.mapType(),
    )
}
