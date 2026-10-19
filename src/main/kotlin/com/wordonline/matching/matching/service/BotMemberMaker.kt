package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.config.PracticeMatchProperties
import com.wordonline.matching.matching.dto.AccountMemberResponseDto
import com.wordonline.matching.matching.domain.BotPersona
import com.wordonline.matching.matching.repository.BotPersonaRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class BotMemberMaker(
    private val botPersonaRepository: BotPersonaRepository,
    private val practiceMatchProperties: PracticeMatchProperties,
) {

    // Bot ratings move with every practice result while the player's stays put, so a bot's MMR is
    // how strong it has proven against players. Drawing from the few nearest keeps the opponent
    // close to the player's level without handing the same player the same bot every time.
    suspend fun getPracticeBotIdNear(mmr: Long): Long {
        val nearest = botPersonaRepository
            .findNearestMatchable(mmr, practiceMatchProperties.nearestBotCount.coerceAtLeast(1))
            .asFlow()
            .toList()
        check(nearest.isNotEmpty()) { "No enabled bot persona is available." }
        return nearest.random().userId
    }

    fun getHospitalityBotId(): Mono<Long> = botPersonaRepository.findHospitality()
        .map(BotPersona::userId)
        .switchIfEmpty(Mono.error(IllegalStateException("No hospitality bot persona is available.")))

    fun getBot(botId: Long): Mono<AccountMemberResponseDto> {
        require(botId < 0) { "Bot user ID must be negative." }

        return botPersonaRepository.findByUserId(botId)
            .switchIfEmpty(Mono.error(IllegalArgumentException("Bot persona not found: userId=$botId")))
            .flatMap { persona ->
                if (!persona.enabled) {
                    Mono.error(IllegalStateException("Bot persona is disabled: userId=$botId"))
                } else {
                    Mono.just(AccountMemberResponseDto("bot@theevilent.com", persona.name))
                }
            }
    }
}
