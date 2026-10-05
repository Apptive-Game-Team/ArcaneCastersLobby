package com.wordonline.matching.server.service

import com.wordonline.matching.server.entity.Server

/**
 * Orders game servers for a session by the worst latency any participant has to them.
 *
 * A match is only as good as its slowest player, so each server is scored by the **maximum**
 * of the participants' pings and the lowest score goes first. Ping is reported by each client
 * (keyed by `servers.id`), never measured by the lobby: the lobby's own distance to a game
 * server says nothing about the players'.
 *
 * A server some participant has no measurement for is [UNKNOWN] and sorts after every fully
 * measured server. Treating a missing ping as "fast" would let a client dodge the penalty just
 * by not reporting, and treating it as 0 would let it win every tie. The sort is stable, so
 * ties - and the all-unknown case, such as bots and PVE - keep discovery order exactly as before.
 */
object GameServerPingSelector {

    const val UNKNOWN: Long = Long.MAX_VALUE

    /** Reported pings outside this range are noise or tampering, not a measurement. */
    const val MAX_PING_MS: Long = 10_000

    fun order(candidates: List<Server>, participantPings: List<Map<Long, Long>>): List<Server> {
        if (participantPings.isEmpty()) return candidates
        return candidates.sortedBy { server -> maxPing(server, participantPings) }
    }

    fun maxPing(server: Server, participantPings: List<Map<Long, Long>>): Long {
        val serverId = server.id ?: return UNKNOWN
        return participantPings.maxOfOrNull { pings -> pings[serverId] ?: UNKNOWN } ?: UNKNOWN
    }

    /** Drops negative and absurd values so one bad report cannot skew or pin a server's score. */
    fun sanitize(pings: Map<Long, Long>?): Map<Long, Long>? =
        pings?.filterValues { it in 0..MAX_PING_MS }?.takeIf { it.isNotEmpty() }
}
