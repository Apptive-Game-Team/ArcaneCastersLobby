package com.wordonline.matching.quest.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.quest.dto.QuestClaimResponseDto
import com.wordonline.matching.quest.service.QuestClaimResult
import com.wordonline.matching.quest.service.QuestService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Explicit claim of one quest, used for `MANUAL` quests such as the adventure-clear chest the player
 * clicks at the end of an adventure. An `AUTO` quest can be claimed here too.
 *
 * Error statuses are returned as [ResponseEntity] rather than thrown, because
 * `GlobalExceptionHandler` turns every unhandled exception into 500.
 */
@RestController
class QuestClaimController(
    private val questService: QuestService,
) {
    /**
     * 200 with the granted rewards; 404 for an unknown or `DEPRECATED` quest; 409 when this user
     * already claimed it; 422 when its condition is not met yet.
     */
    @PostMapping("/api/users/mine/quests/{questId}/claim")
    suspend fun claimQuest(@UserId userId: Long?, @PathVariable questId: Long): ResponseEntity<Any> =
        when (val result = questService.claimQuest(userId!!, questId)) {
            is QuestClaimResult.Claimed -> ResponseEntity.ok(QuestClaimResponseDto(result.rewards))
            QuestClaimResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("quest $questId not found")
            QuestClaimResult.AlreadyClaimed ->
                ResponseEntity.status(HttpStatus.CONFLICT).body("quest $questId is already claimed")
            QuestClaimResult.NotClaimable ->
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body("quest $questId is not completed yet")
        }
}
