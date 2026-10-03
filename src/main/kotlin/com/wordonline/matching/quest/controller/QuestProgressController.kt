package com.wordonline.matching.quest.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.quest.dto.QuestProgressResponseDto
import com.wordonline.matching.quest.service.QuestService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Progress of the quest that unlocks one card or one decoration. Both paths are the ones
 * `CardController` and `DecorationController` served before the quest code moved to Kotlin, and
 * the response shape is unchanged. A card or decoration that no quest grants answers 200 with an
 * empty body, as before.
 */
@RestController
class QuestProgressController(
    private val questService: QuestService,
) {
    @GetMapping("/api/cards/{cardId}/quest-progress")
    suspend fun getQuestProgressByCard(@UserId userId: Long?, @PathVariable cardId: Long): QuestProgressResponseDto? =
        questService.findQuestProgressByCard(userId!!, cardId)

    @GetMapping("/api/users/mine/decorations/{decoId}/quest-progress")
    suspend fun getQuestProgressByDecoration(@UserId userId: Long?, @PathVariable decoId: Long): QuestProgressResponseDto? =
        questService.findQuestProgressByDecoration(userId!!, decoId)
}
