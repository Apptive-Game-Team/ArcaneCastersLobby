package com.wordonline.matching.local.controller

import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.local.dto.LocalResultRequest
import com.wordonline.matching.local.dto.LocalResultResponse
import com.wordonline.matching.local.dto.LocalSessionRequest
import com.wordonline.matching.local.service.LocalResultReportResult
import com.wordonline.matching.local.service.LocalSessionIssueResult
import com.wordonline.matching.local.service.LocalSessionService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

/** Status codes and body shapes of the local session endpoints, which the client branches on. */
@WebFluxTest(controllers = [LocalSessionController::class])
@Import(UserIdResolver::class)
class LocalSessionEndpointsTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var localSessionService: LocalSessionService

    private val userId = 5L

    private fun client() = webTestClient.mutateWith(mockJwt().jwt { it.claim("memberId", userId) }).mutateWith(csrf())

    @Test
    fun `로컬 세션이 꺼져 있으면 503 이다`() {
        runBlocking<Unit> {
            whenever(localSessionService.issue(userId, LocalSessionRequest("PRACTICE"))).thenReturn(LocalSessionIssueResult.Disabled)
        }

        client().post().uri("/api/local-sessions").bodyValue(mapOf("type" to "PRACTICE")).exchange()
            .expectStatus().isEqualTo(503)
    }

    @Test
    fun `열리지 않은 시나리오는 409 다`() {
        runBlocking<Unit> {
            whenever(localSessionService.issue(userId, LocalSessionRequest("PVE", 3L))).thenReturn(LocalSessionIssueResult.ScenarioNotPlayable)
        }

        client().post().uri("/api/local-sessions").bodyValue(mapOf("type" to "PVE", "scenarioId" to 3)).exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `받아들인 결과는 reason 없이 accepted 만 보낸다`() {
        runBlocking<Unit> {
            whenever(localSessionService.report(eq(userId), eq("local-1"), any()))
                .thenReturn(LocalResultReportResult.Decided(LocalResultResponse(true)))
        }

        client().post().uri("/api/local-sessions/local-1/result").bodyValue(LocalResultRequest(outcome = "DRAW")).exchange()
            .expectStatus().isOk
            .expectBody().json("""{"accepted":true}""", true)
    }

    @Test
    fun `거절한 결과도 200 이고 reason 을 보낸다`() {
        runBlocking<Unit> {
            whenever(localSessionService.report(eq(userId), eq("local-1"), any()))
                .thenReturn(LocalResultReportResult.Decided(LocalResultResponse(false, "TOO_SHORT")))
        }

        client().post().uri("/api/local-sessions/local-1/result").bodyValue(LocalResultRequest(outcome = "WIN")).exchange()
            .expectStatus().isOk
            .expectBody().json("""{"accepted":false,"reason":"TOO_SHORT"}""", true)
    }

    @Test
    fun `없는 세션은 404 이미 보고된 세션은 409 다`() {
        runBlocking<Unit> {
            whenever(localSessionService.report(eq(userId), eq("local-404"), any())).thenReturn(LocalResultReportResult.NotFound)
            whenever(localSessionService.report(eq(userId), eq("local-2"), any())).thenReturn(LocalResultReportResult.AlreadyReported)
        }

        client().post().uri("/api/local-sessions/local-404/result").bodyValue(LocalResultRequest()).exchange()
            .expectStatus().isNotFound
        client().post().uri("/api/local-sessions/local-2/result").bodyValue(LocalResultRequest()).exchange()
            .expectStatus().isEqualTo(409)
    }
}
