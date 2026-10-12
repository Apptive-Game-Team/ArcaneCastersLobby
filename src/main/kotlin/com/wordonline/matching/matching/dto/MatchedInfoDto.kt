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
    /** Map kind the game server chose, passed through unvalidated. `null` from a game server that predates it. */
    val mapType: String? = null,
    val type: String = "matchedInfoDto",
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
        webSocketUrl = null,
        mapType = sessionInfo.mapType(),
    )
}
