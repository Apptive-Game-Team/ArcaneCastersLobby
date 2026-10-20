package com.wordonline.matching.local.controller

import com.wordonline.matching.global.config.AccountServerProperties
import com.wordonline.matching.global.config.CorsProperties
import com.wordonline.matching.global.config.WebSecurityConfig
import com.wordonline.matching.local.config.LocalServerProperties
import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.dto.StaticData
import com.wordonline.matching.local.dto.StaticTable
import com.wordonline.matching.local.service.OfflinePackService
import com.wordonline.matching.local.service.StaticDataService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/** `GET /api/offline-pack` through the real [WebSecurityConfig] chain, so the permitAll rule is exercised. */
@WebFluxTest(controllers = [OfflinePackController::class])
@Import(WebSecurityConfig::class, OfflinePackService::class)
@EnableConfigurationProperties(
    CorsProperties::class,
    AccountServerProperties::class,
    LocalServerProperties::class,
    LocalSessionProperties::class,
)
@TestPropertySource(
    properties = [
        "cors.allowed-origins=https://arcanecasters.theevilent.com",
        "team6515.server.account.url=http://account-server:8080",
        "local-server.version=1.3.0",
        "local-sessions.enabled=true",
    ],
)
class OfflinePackEndpointsTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var staticDataService: StaticDataService

    @MockitoBean
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    private val staticData = StaticData(
        schemaVersion = 1,
        staticVersion = "v1",
        generatedAt = Instant.parse("2026-10-12T03:00:00Z"),
        tables = mapOf("magics" to StaticTable(listOf("id", "name", "data"), listOf(listOf(1L, "fire_shot", "{\"a\":1}")))),
    )

    @BeforeEach
    fun givenStaticData() = runBlocking<Unit> {
        whenever(staticDataService.current()).thenReturn(staticData)
    }

    @Test
    fun `로그인 없이 정적 데이터와 로컬 서버 묶음 주소를 받는다`() {
        webTestClient.get().uri("/api/offline-pack").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.schemaVersion").isEqualTo(1)
            .jsonPath("$.staticVersion").isEqualTo("v1")
            .jsonPath("$.staticData.staticVersion").isEqualTo("v1")
            .jsonPath("$.staticData.generatedAt").isEqualTo("2026-10-12T03:00:00Z")
            .jsonPath("$.staticData.tables.magics.columns[2]").isEqualTo("data")
            .jsonPath("$.staticData.tables.magics.rows[0][2]").isEqualTo("{\"a\":1}")
            .jsonPath("$.localSessionsEnabled").isEqualTo(true)
            .jsonPath("$.localServer.version").isEqualTo("1.3.0")
            .jsonPath("$.localServer.minimumVersion").isEqualTo("1.3.0")
            .jsonPath("$.localServer.assets.windows-x64").isEqualTo(
                "https://github.com/Apptive-Game-Team/ArcaneCastersGame/releases/download/v1.3.0/local-server-windows-x64.zip",
            )
            .jsonPath("$.localServer.assets.length()").isEqualTo(4)
    }

    @Test
    fun `가진 staticVersion 을 보내면 staticData 는 null 이다`() {
        webTestClient.get().uri("/api/offline-pack?staticVersion=v1").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.staticVersion").isEqualTo("v1")
            // Sent as an explicit null, not left out.
            .jsonPath("$.staticData").hasJsonPath()
            .jsonPath("$.staticData").isEmpty()
    }
}
