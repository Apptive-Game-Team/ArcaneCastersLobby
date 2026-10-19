package com.wordonline.matching.publicstats.controller

import com.wordonline.matching.global.config.AccountServerProperties
import com.wordonline.matching.global.config.CorsProperties
import com.wordonline.matching.global.config.WebSecurityConfig
import com.wordonline.matching.publicstats.dto.PublicMagicStatisticDto
import com.wordonline.matching.publicstats.dto.PublicMagicStatisticsResponse
import com.wordonline.matching.publicstats.dto.PublicRankingPlayerDto
import com.wordonline.matching.publicstats.dto.PublicRankingResponse
import com.wordonline.matching.publicstats.service.PublicStatsService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/**
 * Response shapes and cross-origin behaviour of `/api/public`, through the real
 * [WebSecurityConfig] filter chain so the permitAll rule and the CORS policy are exercised too.
 */
@WebFluxTest(controllers = [PublicStatsController::class])
@Import(WebSecurityConfig::class)
@EnableConfigurationProperties(CorsProperties::class, AccountServerProperties::class)
@TestPropertySource(
    properties = [
        "cors.allowed-origins=https://arcanecasters.theevilent.com",
        "team6515.server.account.url=http://account-server:8080",
    ],
)
class PublicStatsEndpointsTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var publicStatsService: PublicStatsService

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private val updatedAt = Instant.parse("2026-10-11T03:00:00Z")

    @Test
    fun `랭킹은 로그인 없이 조회되고 이메일과 사용자 id 필드가 없다`() {
        runBlocking<Unit> {
            whenever(publicStatsService.getRanking()).thenReturn(
                PublicRankingResponse(updatedAt, listOf(PublicRankingPlayerDto(1, "닉네임", 1234, 40, 21, 2))),
            )
        }

        webTestClient.get().uri("/api/public/rankings").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.updatedAt").isEqualTo("2026-10-11T03:00:00Z")
            .jsonPath("$.players[0].rank").isEqualTo(1)
            .jsonPath("$.players[0].name").isEqualTo("닉네임")
            .jsonPath("$.players[0].mmr").isEqualTo(1234)
            .jsonPath("$.players[0].wins").isEqualTo(40)
            .jsonPath("$.players[0].losses").isEqualTo(21)
            .jsonPath("$.players[0].draws").isEqualTo(2)
            .jsonPath("$.players[0].email").doesNotExist()
            .jsonPath("$.players[0].id").doesNotExist()
            .jsonPath("$.players[0].userId").doesNotExist()
            .jsonPath("$.players[0].length()").isEqualTo(6)
    }

    @Test
    fun `마법 통계는 로그인 없이 조회되고 약속한 필드를 가진다`() {
        runBlocking<Unit> {
            whenever(publicStatsService.getMagicStatistics()).thenReturn(
                PublicMagicStatisticsResponse(
                    updatedAt = updatedAt,
                    from = Instant.parse("2026-09-11T00:00:00Z"),
                    to = Instant.parse("2026-10-11T00:00:00Z"),
                    totalGames = 812,
                    magics = listOf(PublicMagicStatisticDto(12, "Fireball", null, 300, 0.369, null, 4100)),
                ),
            )
        }

        webTestClient.get().uri("/api/public/statistics/magics").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.from").isEqualTo("2026-09-11T00:00:00Z")
            .jsonPath("$.to").isEqualTo("2026-10-11T00:00:00Z")
            .jsonPath("$.totalGames").isEqualTo(812)
            .jsonPath("$.magics[0].magicId").isEqualTo(12)
            .jsonPath("$.magics[0].name").isEqualTo("Fireball")
            .jsonPath("$.magics[0].prefab").isEmpty
            .jsonPath("$.magics[0].decks").isEqualTo(300)
            .jsonPath("$.magics[0].pickRate").isEqualTo(0.369)
            .jsonPath("$.magics[0].winRate").isEmpty
            .jsonPath("$.magics[0].casts").isEqualTo(4100)
    }

    @Test
    fun `공개 경로의 preflight 는 임의의 origin 에 와일드카드를 돌려주고 credentials 를 허용하지 않는다`() {
        webTestClient.options().uri("http://127.0.0.1/api/public/rankings")
            .header(HttpHeaders.ORIGIN, "https://some-random-site.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
    }

    @Test
    fun `공개 경로는 GET 외의 메서드 preflight 를 거절한다`() {
        webTestClient.options().uri("http://127.0.0.1/api/public/rankings")
            .header(HttpHeaders.ORIGIN, "https://some-random-site.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `공개 경로의 실제 GET 응답에도 와일드카드 origin 이 실린다`() {
        runBlocking<Unit> {
            whenever(publicStatsService.getRanking()).thenReturn(PublicRankingResponse(updatedAt, emptyList()))
        }

        webTestClient.get().uri("http://127.0.0.1/api/public/rankings")
            .header(HttpHeaders.ORIGIN, "https://some-random-site.example")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
    }

    @Test
    fun `다른 경로는 기존 credentials 정책을 유지해 낯선 origin 을 거절한다`() {
        webTestClient.options().uri("http://127.0.0.1/api/match")
            .header(HttpHeaders.ORIGIN, "https://some-random-site.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `다른 경로는 허용된 origin 에 credentials 를 허용한다`() {
        webTestClient.options().uri("http://127.0.0.1/api/match")
            .header(HttpHeaders.ORIGIN, "https://arcanecasters.theevilent.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://arcanecasters.theevilent.com")
            .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
    }
}
