package com.wordonline.matching.auth.dto

import java.time.Instant

/**
 * [opponentName] is resolved server-side through `AccountClient` (issue #27) so bot opponents,
 * whose negative ids the account server does not know, still show a name. A lookup failure for
 * one opponent must not fail the whole page, so it is left null rather than the request failing.
 */
data class UserGameRecordResponseDto(
    val opponentId: Long,
    val opponentName: String?,
    val result: String,
    val gameType: String,
    val playedAt: Instant,
)
