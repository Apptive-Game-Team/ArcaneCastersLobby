package com.wordonline.matching.playground

import java.time.Instant

data class PlaygroundInfo(
    val sessionId: String,
    val server: String,
    val webSocketUrl: String,
    val ownerId: Long,
    val expiresAt: Instant,
)

data class CreatePlaygroundRequest(val ownerId: Long)
