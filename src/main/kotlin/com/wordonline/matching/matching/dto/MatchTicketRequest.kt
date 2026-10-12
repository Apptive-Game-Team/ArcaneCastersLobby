package com.wordonline.matching.matching.dto

enum class MatchDeckMode {
    SELECTED,
    RANDOM,
}

/** One client-measured round trip to a game server from `GET /api/match/servers`. */
data class ServerPingDto(
    val serverId: Long,
    val rttMs: Long,
)

data class MatchTicketRequest(
    val deckMode: MatchDeckMode = MatchDeckMode.SELECTED,
    /**
     * Latency from this client to each game server, used to place the match on the server with
     * the lowest worst-case ping. Optional: a client that sends none still matches, its servers
     * just rank last.
     */
    val serverPings: List<ServerPingDto>? = null,
)
