package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.repository.BotQueueRepository
import com.wordonline.matching.matching.repository.MatchTicketRepository
import org.springframework.stereotype.Service

/**
 * Reads the live tickets held by bots: `QUEUED`, `ALLOCATING` or `MATCHED`.
 *
 * There is no redis index of bot tickets, so this reads `matching:active:<botId>` for every bot
 * persona. The bot roster is tens of rows, and a terminal ticket releases that key, so whatever
 * the key still names is the bot's live ticket.
 */
@Service
class BotTicketCensus(
    private val botQueueRepository: BotQueueRepository,
    private val matchTicketRepository: MatchTicketRepository,
) {
    suspend fun liveBotTickets(): List<MatchTicket> =
        botQueueRepository.findAllBotUserIds()
            .mapNotNull { matchTicketRepository.getActive(it) }
            .filter { it.state in LIVE_STATES }

    companion object {
        val LIVE_STATES = setOf(MatchTicketState.QUEUED, MatchTicketState.ALLOCATING, MatchTicketState.MATCHED)
    }
}
