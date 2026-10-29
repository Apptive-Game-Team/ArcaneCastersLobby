package com.wordonline.matching.progression

import com.wordonline.matching.auth.service.UserIdResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [ProgressionController::class])
@AutoConfigureWebTestClient(timeout = "PT30S")
@Import(UserIdResolver::class)
class ProgressionEndpointsTest {
    @Autowired private lateinit var client: WebTestClient
    @MockitoBean private lateinit var service: ProgressionService

    @Test
    fun `레벨 보상 조회는 인증된 계정의 고정 보상만 반환한다`() {
        runBlocking { whenever(service.rewards(7)).thenReturn(ProgressionRewardsResponse("NATURE",
            listOf(ProgressionLevelReward(10, 900, 1, 6, listOf(ProgressionFixedReward(43, "tornado_strike", 2)))))) }
        client.mutateWith(mockJwt().jwt { it.claim("memberId", 7L) }).get()
            .uri("/api/users/mine/progression/rewards").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.faction").isEqualTo("NATURE")
            .jsonPath("$.levels[0].cards[0].magicName").isEqualTo("tornado_strike")
            .jsonPath("$.levels[0].rewardSlots").isEqualTo(6)
        client.mutateWith(mockJwt().jwt { it.claim("memberId", 8L) }).get()
            .uri("/api/users/mine/progression/rewards").exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `성장 조회는 토큰의 사용자만 사용한다`() {
        runBlocking { whenever(service.sync(7)).thenReturn(ProgressionResponse("HELL", 2, 100, 0, 100, 20)) }
        client.mutateWith(mockJwt().jwt { it.claim("memberId", 7L) }).mutateWith(csrf())
            .post().uri("/api/users/mine/progression/sync").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.level").isEqualTo(2).jsonPath("$.currency").isEqualTo(20)
    }

    @Test
    fun `잘못된 세력은 400 이미 선택한 다른 세력은 409`() {
        val authenticated = client.mutateWith(mockJwt().jwt { it.claim("memberId", 7L) }).mutateWith(csrf())
        authenticated.post().uri("/api/users/mine/progression/faction").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"faction":"INVALID"}""").exchange().expectStatus().isBadRequest
        runBlocking { whenever(service.choose(7, "NATURE")).thenReturn(null) }
        authenticated.post().uri("/api/users/mine/progression/faction").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"faction":"NATURE"}""").exchange().expectStatus().isEqualTo(409)
    }
}
