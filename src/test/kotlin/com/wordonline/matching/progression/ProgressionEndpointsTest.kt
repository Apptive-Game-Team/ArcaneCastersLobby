package com.wordonline.matching.progression

import com.wordonline.matching.auth.service.UserIdResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(controllers = [ProgressionController::class])
@Import(UserIdResolver::class)
class ProgressionEndpointsTest {
    @Autowired private lateinit var client: WebTestClient
    @MockitoBean private lateinit var service: ProgressionService

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
