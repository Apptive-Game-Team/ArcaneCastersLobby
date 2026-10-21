package com.wordonline.matching.local.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.wordonline.matching.auth.dto.UserDetailResponseDto
import java.time.Instant

/**
 * Body of `POST /api/local-sessions`. Fields are nullable and strings so a malformed body is
 * answered with 400 by the service rather than 500 by the global exception handler.
 */
data class LocalSessionRequest(
    /** `PRACTICE` or `PVE`. */
    val type: String? = null,
    /** Required for `PVE`, ignored for `PRACTICE`. */
    val scenarioId: Long? = null,
)

/** Response of `POST /api/local-sessions`, which the client forwards to its local server. */
data class LocalSessionResponse(
    val sessionId: String,
    val type: String,
    val scenarioId: Long?,
    val leftUserId: Long,
    val rightUserId: Long,
    val leftUser: UserDetailResponseDto,
    val rightUser: UserDetailResponseDto,
    /** The selected deck, one magic id per card, as the game server receives `leftDeckCardIds`. */
    val leftDeckCardIds: List<Long>,
    val ownedMagicIds: List<Long>,
    val noviceProgress: Double,
    val staticVersion: String,
    val issuedAt: Instant,
)

/**
 * Body of `POST /api/local-sessions/{sessionId}/result`, the local server's result as the client
 * forwards it. Same shape as the game server's `GameResultDto`; nullable for the same reason as
 * [LocalSessionRequest].
 */
data class LocalResultRequest(
    /** `WIN`, `DRAW` or `ABANDONED`. */
    val outcome: String? = null,
    val winUserId: Long? = null,
    val lossUserId: Long? = null,
    val durationMillis: Long? = null,
    val frameCount: Long? = null,
    val tickRate: Int? = null,
    val localServerVersion: String? = null,
    val decks: List<LocalResultCount>? = null,
    val magics: List<LocalResultCount>? = null,
)

/** One deck entry (copies in the deck) or one magic entry (times cast) of a participant. */
data class LocalResultCount(
    val userId: Long? = null,
    val magicId: Long? = null,
    val count: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LocalResultResponse(
    val accepted: Boolean,
    val reason: String? = null,
)
