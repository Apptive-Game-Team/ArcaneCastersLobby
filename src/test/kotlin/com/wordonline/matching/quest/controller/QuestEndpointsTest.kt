package com.wordonline.matching.quest.controller

import com.wordonline.matching.auth.controller.UserController
import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.auth.service.UserStatisticsService
import com.wordonline.matching.quest.domain.QuestState
import com.wordonline.matching.quest.dto.QuestProgressResponseDto
import com.wordonline.matching.quest.dto.QuestRewardDto
import com.wordonline.matching.quest.dto.QuestSummaryResponseDto
import com.wordonline.matching.quest.dto.QuestSummaryRewardDto
import com.wordonline.matching.quest.service.QuestClaimResult
import com.wordonline.matching.quest.service.QuestService
import com.wordonline.matching.session.service.LegacyGameMatchService
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
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

/** Response shapes of the quest endpoints, which the Unity client parses. */
@WebFluxTest(controllers = [QuestProgressController::class, QuestClaimController::class, UserController::class])
@Import(UserIdResolver::class)
class QuestEndpointsTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var questService: QuestService

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var userStatisticsService: UserStatisticsService

    @MockitoBean
    private lateinit var legacyGameMatchService: LegacyGameMatchService

    private val userId = 1L

    private fun client() = webTestClient.mutateWith(mockJwt().jwt { it.claim("memberId", userId) })

    @Test
    @DisplayName("카드의_퀘스트_진행상황_조회_엔드포인트_호출_성공")
    fun getQuestProgressByCard_Success() {
        runBlocking {
            whenever(questService.findQuestProgressByCard(userId, 1L))
                .thenReturn(QuestProgressResponseDto(QuestState.IN_PROGRESS, 50, 100))
        }

        client().get().uri("/api/cards/{cardId}/quest-progress", 1L)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json("""{"state":"IN_PROGRESS","progress":50,"requireValue":100}""", true)
    }

    @Test
    @DisplayName("장식의_퀘스트_진행상황_조회_엔드포인트_호출_성공")
    fun getQuestProgressByDecoration_Success() {
        runBlocking {
            whenever(questService.findQuestProgressByDecoration(userId, 4L))
                .thenReturn(QuestProgressResponseDto(QuestState.COMPLETED, 3, 3))
        }

        client().get().uri("/api/users/mine/decorations/{decoId}/quest-progress", 4L)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json("""{"state":"COMPLETED","progress":3,"requireValue":3}""", true)
    }

    @Test
    @DisplayName("퀘스트_check_응답은_rewards_배열이고_보상마다_rewardKey_가_있다")
    fun checkMyQuests_Shape() {
        runBlocking {
            whenever(questService.checkQuestsWithRewards(userId)).thenReturn(
                listOf(
                    QuestRewardDto("MAGIC", 10L, null, 3, 1L),
                    QuestRewardDto("DECORATION", 20L, null, 1, 2L),
                    QuestRewardDto("CHEST", 1L, "forest_chest", 1, 11L),
                ),
            )
        }

        client().mutateWith(csrf()).post().uri("/api/users/mine/quests/check")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json(
                """
                {"rewards":[
                  {"rewardType":"MAGIC","rewardId":10,"rewardKey":null,"amount":3,"questId":1},
                  {"rewardType":"DECORATION","rewardId":20,"rewardKey":null,"amount":1,"questId":2},
                  {"rewardType":"CHEST","rewardId":1,"rewardKey":"forest_chest","amount":1,"questId":11}
                ]}
                """,
                true,
            )
    }

    @Test
    @DisplayName("내_퀘스트_목록_응답은_퀘스트마다_조건_상태_진행_보상을_담은_배열이다")
    fun getMyQuests_Shape() {
        runBlocking {
            whenever(questService.findMyQuests(userId)).thenReturn(
                listOf(
                    QuestSummaryResponseDto(
                        questId = 1L,
                        conditionType = "STAGE_CLEAR",
                        conditionTargetId = null,
                        state = QuestState.IN_PROGRESS,
                        progress = 2,
                        requireValue = 3,
                        claimMode = "AUTO",
                        claimable = false,
                        rewards = listOf(
                            QuestSummaryRewardDto("MAGIC", 83L, null, 2),
                            QuestSummaryRewardDto("APPEARANCE", 6L, "grass", 1),
                        ),
                    ),
                    QuestSummaryResponseDto(
                        questId = 2L,
                        conditionType = "TOTAL_WIN",
                        conditionTargetId = 7L,
                        state = QuestState.PENDING,
                        progress = 0,
                        requireValue = 10,
                        claimMode = "AUTO",
                        claimable = false,
                        rewards = emptyList(),
                    ),
                    QuestSummaryResponseDto(
                        questId = 11L,
                        conditionType = "ADVENTURE_CLEAR",
                        conditionTargetId = 1L,
                        state = QuestState.IN_PROGRESS,
                        progress = 3,
                        requireValue = 3,
                        claimMode = "MANUAL",
                        claimable = true,
                        rewards = listOf(QuestSummaryRewardDto("CHEST", 1L, "forest_chest", 1)),
                    ),
                ),
            )
        }

        client().get().uri("/api/users/mine/quests")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json(
                """
                [
                  {"questId":1,"conditionType":"STAGE_CLEAR","conditionTargetId":null,"state":"IN_PROGRESS",
                   "progress":2,"requireValue":3,"claimMode":"AUTO","claimable":false,"rewards":[{"rewardType":"MAGIC","rewardId":83,"rewardKey":null,"amount":2},
                              {"rewardType":"APPEARANCE","rewardId":6,"rewardKey":"grass","amount":1}]},
                  {"questId":2,"conditionType":"TOTAL_WIN","conditionTargetId":7,"state":"PENDING",
                   "progress":0,"requireValue":10,"claimMode":"AUTO","claimable":false,"rewards":[]},
                  {"questId":11,"conditionType":"ADVENTURE_CLEAR","conditionTargetId":1,"state":"IN_PROGRESS",
                   "progress":3,"requireValue":3,"claimMode":"MANUAL","claimable":true,
                   "rewards":[{"rewardType":"CHEST","rewardId":1,"rewardKey":"forest_chest","amount":1}]}
                ]
                """,
                true,
            )
    }

    private fun claim(questId: Long) =
        client().mutateWith(csrf()).post().uri("/api/users/mine/quests/{questId}/claim", questId).exchange()

    @Test
    @DisplayName("퀘스트_claim_응답은_지급한_보상마다_rewardType_rewardId_rewardKey_amount_를_담은_rewards_배열이다")
    fun claimQuest_Shape() {
        runBlocking {
            whenever(questService.claimQuest(userId, 11L)).thenReturn(
                QuestClaimResult.Claimed(listOf(QuestSummaryRewardDto("CHEST", 1L, "forest_chest", 1))),
            )
        }

        claim(11L)
            .expectStatus().isOk
            .expectBody()
            .json("""{"rewards":[{"rewardType":"CHEST","rewardId":1,"rewardKey":"forest_chest","amount":1}]}""", true)
    }

    @Test
    @DisplayName("없거나_DEPRECATED_인_퀘스트는_404_이미_받은_퀘스트는_409_조건을_못_채운_퀘스트는_422_다")
    fun claimQuest_ErrorStatuses() {
        runBlocking {
            whenever(questService.claimQuest(userId, 5L)).thenReturn(QuestClaimResult.NotFound)
            whenever(questService.claimQuest(userId, 11L)).thenReturn(QuestClaimResult.AlreadyClaimed)
            whenever(questService.claimQuest(userId, 12L)).thenReturn(QuestClaimResult.NotClaimable)
        }

        claim(5L).expectStatus().isNotFound.expectBody(String::class.java).isEqualTo("quest 5 not found")
        claim(11L).expectStatus().isEqualTo(409).expectBody(String::class.java).isEqualTo("quest 11 is already claimed")
        claim(12L).expectStatus().isEqualTo(422).expectBody(String::class.java).isEqualTo("quest 12 is not completed yet")
    }

    @Test
    @DisplayName("인증하지_않은_claim_은_401_이고_서비스를_부르지_않는다")
    fun claimQuest_RequiresAuthentication() {
        webTestClient.mutateWith(csrf()).post().uri("/api/users/mine/quests/{questId}/claim", 11L)
            .exchange()
            .expectStatus().isUnauthorized

        verifyBlocking(questService, never()) { claimQuest(any(), any()) }
    }
}
