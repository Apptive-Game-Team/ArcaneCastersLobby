package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.config.BotQueueProperties
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.repository.BotQueueRepository
import com.wordonline.matching.server.entity.Server
import org.springframework.stereotype.Service

/**
 * Orders game servers for a bot-vs-bot session by spare bot capacity, largest first.
 *
 * Bot tickets carry no pings, so the ping order would keep discovery order and every bot game
 * would land on the first server. Spare capacity is the server's bot game target
 * (`COALESCE(target_bot_sessions, default-target-per-server)`, 0 for a server that is not
 * `ACTIVE` in its row) minus the bot games the lobby already placed there, counted from `MATCHED`
 * bot tickets (two per game). The sort is stable, so ties keep discovery order.
 */
@Service
class BotSessionPlacement(
    private val properties: BotQueueProperties,
    private val botQueueRepository: BotQueueRepository,
    private val botTicketCensus: BotTicketCensus,
) {
    suspend fun order(candidates: List<Server>): List<Server> {
        if (candidates.size < 2) return candidates
        val targets = botQueueRepository.findActiveGameServerTargets(properties.defaultTargetPerServer)
            .associate { it.serverId to it.targetBotSessions }
        val placedTickets = botTicketCensus.liveBotTickets()
            .filter { it.state == MatchTicketState.MATCHED }
            .groupingBy { it.serverId }
            .eachCount()
        return orderBySpareCapacity(candidates, targets, placedTickets)
    }

    companion object {
        fun orderBySpareCapacity(
            candidates: List<Server>,
            targets: Map<Long, Int>,
            placedTickets: Map<Long?, Int>,
        ): List<Server> = candidates.sortedByDescending { server ->
            val target = targets[server.id] ?: 0
            val placedGames = ((placedTickets[server.id] ?: 0) + 1) / 2
            target - placedGames
        }
    }
}
