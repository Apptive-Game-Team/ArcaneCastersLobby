package com.wordonline.matching.matching.dto

/** A game server a client may measure its ping to, via `GET <url>/healthcheck`. */
data class GameServerEndpointDto(
    val serverId: Long,
    val url: String,
)
