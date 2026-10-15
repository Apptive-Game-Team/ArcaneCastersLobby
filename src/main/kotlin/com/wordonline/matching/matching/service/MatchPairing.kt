package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.MatchTicket

/**
 * Picks the pairs one matching tick allocates.
 *
 * Bot tickets (negative user ids, enqueued by [BotQueueFeeder]) pair only with bot tickets and
 * human tickets only with human tickets. Within each group the rule is the one human matching
 * always had: the adjacent pair with the smallest MMR gap after sorting by MMR.
 *
 * At most one human pair and one bot pair per tick, human pair first. Matching one pair per tick
 * across both groups would let a queue full of bots take ticks away from people; with a pair per
 * group, a waiting bot pair never delays a human pair, and the human pair is still claimed and
 * placed before the bot pair.
 */
object MatchPairing {

    fun pairsToMatch(candidates: List<MatchTicket>): List<Pair<MatchTicket, MatchTicket>> {
        val (bots, humans) = candidates.partition { it.userId < 0 }
        return listOfNotNull(closestPair(humans), closestPair(bots))
    }

    private fun closestPair(tickets: List<MatchTicket>): Pair<MatchTicket, MatchTicket>? {
        if (tickets.size < 2) return null
        return tickets.sortedBy { it.mmr }.zipWithNext().minBy { (left, right) -> right.mmr - left.mmr }
    }
}
