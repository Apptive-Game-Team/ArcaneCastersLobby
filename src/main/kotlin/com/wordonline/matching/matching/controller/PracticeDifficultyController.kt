package com.wordonline.matching.matching.controller

import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.matching.domain.PracticeDifficulty
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.PracticeDifficultyOverview
import com.wordonline.matching.matching.service.GameMatchService
import com.wordonline.matching.matching.service.PracticeDifficultyService
import com.wordonline.matching.server.service.GameServerPingSelector
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/match/practice/difficulties")
class PracticeDifficultyController(
    private val difficulties: PracticeDifficultyService,
    private val matches: GameMatchService,
) {
    @GetMapping
    suspend fun overview(@UserId userId: Long?): PracticeDifficultyOverview = difficulties.overview(userId!!)

    @PostMapping("/{id}")
    suspend fun start(
        @UserId userId: Long?,
        @PathVariable id: String,
        @RequestParam(required = false) pings: String?,
    ): ResponseEntity<MatchedInfoDto> {
        val tier = PracticeDifficulty.entries.find { it.name == id } ?: return ResponseEntity.badRequest().build()
        val bot = difficulties.select(tier) ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        return ResponseEntity.ok(matches.matchSelectedPractice(userId!!, bot, GameServerPingSelector.parse(pings)))
    }
}
