package com.wordonline.matching.chest.controller

import com.wordonline.matching.appearance.controller.AppearanceController
import com.wordonline.matching.appearance.dto.AppearanceResponseDto
import com.wordonline.matching.appearance.service.AppearanceSelection
import com.wordonline.matching.appearance.service.AppearanceService
import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.chest.dto.ChestRewardDto
import com.wordonline.matching.chest.dto.UnopenedChestResponseDto
import com.wordonline.matching.chest.service.ChestOpenResult
import com.wordonline.matching.chest.service.ChestService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/** Response shapes and statuses of the appearance and chest endpoints, which the Unity client parses. */
@WebFluxTest(controllers = [AppearanceController::class, ChestController::class])
@Import(UserIdResolver::class)
class AppearanceAndChestEndpointsTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var appearanceService: AppearanceService

    @MockitoBean
    private lateinit var chestService: ChestService

    private val userId = 1L

    private fun client() = webTestClient.mutateWith(mockJwt().jwt { it.claim("memberId", userId) })

    @Test
    @DisplayName("외형_목록은_catalog_전체를_key_sortOrder_owned_selected_로_내려준다")
    fun getMyAppearances_Shape() {
        runBlocking {
            whenever(appearanceService.findMyAppearances(userId)).thenReturn(
                listOf(
                    AppearanceResponseDto("default", 0, owned = true, selected = false),
                    AppearanceResponseDto("storm", 1, owned = true, selected = true),
                    AppearanceResponseDto("blaze", 2, owned = false, selected = false),
                ),
            )
        }

        client().get().uri("/api/users/mine/appearances")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json(
                """
                [
                  {"key":"default","sortOrder":0,"owned":true,"selected":false},
                  {"key":"storm","sortOrder":1,"owned":true,"selected":true},
                  {"key":"blaze","sortOrder":2,"owned":false,"selected":false}
                ]
                """,
                true,
            )
    }

    private fun putAppearance(body: String) =
        client().mutateWith(csrf()).put().uri("/api/users/mine/appearance")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    @Test
    @DisplayName("가진_외형을_고르면_200_과_고른_key_를_돌려준다")
    fun selectAppearance_Ok() {
        runBlocking {
            whenever(appearanceService.selectAppearance(userId, "storm")).thenReturn(AppearanceSelection.Selected("storm"))
        }

        putAppearance("""{"appearance":"storm"}""")
            .expectStatus().isOk
            .expectBody().json("""{"appearance":"storm"}""", true)
    }

    @Test
    @DisplayName("없는_key_는_404_가지지_않은_key_는_403_사용자_행이_없으면_404_다")
    fun selectAppearance_ErrorStatuses() {
        runBlocking {
            whenever(appearanceService.selectAppearance(userId, "nope")).thenReturn(AppearanceSelection.UnknownKey)
            whenever(appearanceService.selectAppearance(userId, "golem")).thenReturn(AppearanceSelection.NotOwned)
            whenever(appearanceService.selectAppearance(userId, "default")).thenReturn(AppearanceSelection.UserNotFound)
        }

        putAppearance("""{"appearance":"nope"}""").expectStatus().isNotFound
        putAppearance("""{"appearance":"golem"}""").expectStatus().isForbidden
        putAppearance("""{"appearance":"default"}""").expectStatus().isNotFound
    }

    @Test
    @DisplayName("appearance_가_없거나_비면_400_이고_service_를_부르지_않는다")
    fun selectAppearance_MissingKeyIsBadRequest() {
        putAppearance("""{}""").expectStatus().isBadRequest
        putAppearance("""{"appearance":" "}""").expectStatus().isBadRequest
        verifyBlocking(appearanceService, never()) { selectAppearance(any(), any()) }
    }

    @Test
    @DisplayName("상자_목록은_user_chests_id_와_상자_key_와_내용물_미리보기를_담는다")
    fun getMyChests_Shape() {
        runBlocking {
            whenever(chestService.findMyChests(userId)).thenReturn(
                listOf(
                    UnopenedChestResponseDto(
                        id = 15L,
                        chestId = 1L,
                        chestKey = "forest_chest",
                        acquiredAt = Instant.parse("2026-10-02T12:00:00Z"),
                        rewards = listOf(ChestRewardDto("APPEARANCE", 6L, "grass", 1)),
                    ),
                ),
            )
        }

        client().get().uri("/api/users/mine/chests")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json(
                """
                [{"id":15,"chestId":1,"chestKey":"forest_chest","acquiredAt":"2026-10-02T12:00:00Z",
                  "rewards":[{"rewardType":"APPEARANCE","rewardId":6,"rewardKey":"grass","amount":1}]}]
                """,
                true,
            )
    }

    @Test
    @DisplayName("상자를_열면_200_과_지급한_보상을_돌려준다")
    fun openChest_Ok() {
        runBlocking {
            whenever(chestService.openChest(userId, 15L)).thenReturn(
                ChestOpenResult.Opened(listOf(ChestRewardDto("APPEARANCE", 6L, "grass", 1))),
            )
        }

        client().mutateWith(csrf()).post().uri("/api/users/mine/chests/{id}/open", 15L)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json("""{"rewards":[{"rewardType":"APPEARANCE","rewardId":6,"rewardKey":"grass","amount":1}]}""", true)
    }

    @Test
    @DisplayName("없는_상자는_404_이미_연_상자는_409_다")
    fun openChest_ErrorStatuses() {
        runBlocking {
            whenever(chestService.openChest(userId, 16L)).thenReturn(ChestOpenResult.NotFound)
            whenever(chestService.openChest(userId, 17L)).thenReturn(ChestOpenResult.AlreadyOpened)
        }

        client().mutateWith(csrf()).post().uri("/api/users/mine/chests/{id}/open", 16L)
            .exchange().expectStatus().isNotFound
        client().mutateWith(csrf()).post().uri("/api/users/mine/chests/{id}/open", 17L)
            .exchange().expectStatus().isEqualTo(409)
    }
}
