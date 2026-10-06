package com.wordonline.matching.matching.client

import com.wordonline.matching.global.service.LocalizationService
import com.wordonline.matching.matching.dto.AccountMemberResponseDto
import com.wordonline.matching.matching.service.BotMemberMaker
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.i18n.LocaleContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Service
class AccountClient(
    builder: WebClient.Builder,
    @Value("\${team6515.server.account.url}") accountServerUrl: String,
    private val localizationService: LocalizationService,
    private val botMemberMaker: BotMemberMaker,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = builder.baseUrl(accountServerUrl).build()

    fun getMember(memberId: Long): Mono<AccountMemberResponseDto> {
        if (memberId < 0) return botMemberMaker.getBot(memberId)

        return webClient.get().uri("/api/members/$memberId")
            .retrieve()
            .onStatus({ it != HttpStatus.OK }) {
                log.info("[Account Client] Status Code : {}", it.statusCode())
                getException()
            }
            .bodyToMono<AccountMemberResponseDto>()
    }

    suspend fun getMemberSuspend(memberId: Long): AccountMemberResponseDto = getMember(memberId).awaitSingle()

    fun searchMembers(query: String, limit: Int = 20): reactor.core.publisher.Flux<AccountMemberResponseDto> {
        if (query.isBlank()) return reactor.core.publisher.Flux.empty()

        return webClient.get().uri { builder ->
            builder.path("/api/members/search")
                .queryParam("query", query)
                .queryParam("limit", limit)
                .build()
        }
            .retrieve()
            .bodyToFlux(AccountMemberResponseDto::class.java)
    }

    suspend fun searchMembersSuspend(query: String, limit: Int = 20): List<AccountMemberResponseDto> =
        searchMembers(query, limit).collectList().awaitSingle()

    private fun getException(): Mono<Throwable> = Mono.deferContextual { ctx ->
        val localeContext = ctx.get(LocaleContext::class.java)
        Mono.error(IllegalArgumentException(localizationService.getMessage(localeContext, "error.member.not.found")))
    }
}
