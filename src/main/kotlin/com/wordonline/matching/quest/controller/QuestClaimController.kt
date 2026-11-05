package com.wordonline.matching.quest.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.quest.dto.QuestClaimResponseDto
import com.wordonline.matching.quest.service.QuestClaimResult
import com.wordonline.matching.quest.service.QuestService
import com.wordonline.matching.progression.RewardClaimPolicy
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestHeader

/**
 * MANUAL과 AUTO 퀘스트를 명시적으로 수령한다. 없는 퀘스트는 404, 이미 수령했으면 409,
 * 조건을 채우지 못했으면 422로 반환한다.
 */
@RestController
class QuestClaimController(
    private val questService: QuestService,
    private val rewardClaimPolicy: RewardClaimPolicy = RewardClaimPolicy(),
) {
    /**
     * 200 with the granted rewards; 404 for an unknown or `DEPRECATED` quest; 409 when this user
     * already claimed it; 422 when its condition is not met yet.
     */
    @PostMapping("/api/users/mine/quests/{questId}/claim")
    suspend fun claimQuest(
        @UserId userId: Long?,
        @PathVariable questId: Long,
        @RequestHeader(value = "X-Reward-Claims", required = false) capability: String? = null,
    ): ResponseEntity<Any> =
        when (val result = questService.claimQuest(userId!!, questId, manualClaims = rewardClaimPolicy.manual(capability))) {
            is QuestClaimResult.Claimed -> ResponseEntity.ok(QuestClaimResponseDto(result.rewards))
            QuestClaimResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("quest $questId not found")
            QuestClaimResult.AlreadyClaimed ->
                ResponseEntity.status(HttpStatus.CONFLICT).body("quest $questId is already claimed")
            QuestClaimResult.NotClaimable ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("quest $questId is not completed yet")
        }
}
