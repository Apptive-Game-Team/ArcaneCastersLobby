package com.wordonline.matching.adventure.controller

import com.wordonline.matching.adventure.dto.AdventuresResponse
import com.wordonline.matching.adventure.service.AdventureService
import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.service.GameMatchService
import com.wordonline.matching.server.service.GameServerPingSelector
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api")
class AdventureController(
    private val adventureService: AdventureService,
    private val gameMatchService: GameMatchService,
) {

    @GetMapping("/adventures")
    fun getAdventures(@UserId userId: Long?): Mono<AdventuresResponse> = adventureService.updateUserAdventures(userId!!)
        .then(adventureService.getAdventures(userId))


    @GetMapping("/scenarios/{scenarioId}/play")
    suspend fun playStage(
        @UserId userId: Long?,
        @PathVariable scenarioId: Long,
        @RequestParam(required = false) pings: String?,
    ): MatchedInfoDto {
        return gameMatchService.matchPVE(userId!!, scenarioId, GameServerPingSelector.parse(pings))
    }
}
