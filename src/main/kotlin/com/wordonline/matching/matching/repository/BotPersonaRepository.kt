package com.wordonline.matching.matching.repository

import com.wordonline.matching.matching.domain.BotPersona
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

/**
 * Which bot personas may be matched, against a `bot_personas` row aliased `bp`. The game server
 * uses the same SQL (bot matching contract, section 2).
 *
 * The hospitality bot is excluded: it is reached only through the novice path. A `RULE` persona
 * is always eligible; an LLM persona needs a key row for its provider whose `unavailable_until`
 * is unset or already past. The comparison stays in SQL against `CURRENT_TIMESTAMP` because the
 * column is `timestamp without time zone` and the servers may run in different time zones.
 */
const val MATCHABLE_BOT_PERSONA_CONDITION = """
bp.enabled = TRUE
  AND bp.hospitality = FALSE
  AND (bp.brain_type = 'RULE'
       OR EXISTS (SELECT 1 FROM llm_api_keys k
                  WHERE k.provider = bp.brain_type
                    AND (k.unavailable_until IS NULL OR k.unavailable_until <= CURRENT_TIMESTAMP)))
"""

interface BotPersonaRepository : R2dbcRepository<BotPersona, Long> {
    fun findByUserId(userId: Long): Mono<BotPersona>

    // Excludes the hospitality bot on purpose. It is enabled, so without this clause an ordinary
    // player would occasionally draw the opponent that is built to lose, which is not a practice
    // match at all. The same condition also drops an LLM bot whose provider key is missing or
    // currently unavailable, so a practice opponent never starts without a working brain.
    @Query(
        """
        SELECT bp.user_id, bp.name, bp.enabled, bp.hospitality
        FROM bot_personas bp
        WHERE $MATCHABLE_BOT_PERSONA_CONDITION
        ORDER BY RANDOM()
        LIMIT 1
        """
    )
    fun findRandomEnabled(): Mono<BotPersona>

    @Query(
        """
        SELECT user_id, name, enabled, hospitality
        FROM bot_personas
        WHERE enabled = TRUE
          AND hospitality = TRUE
        ORDER BY user_id
        LIMIT 1
        """
    )
    fun findHospitality(): Mono<BotPersona>
}
