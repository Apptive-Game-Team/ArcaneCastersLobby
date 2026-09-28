package com.wordonline.matching.auth.controller

import com.wordonline.matching.auth.dto.UserResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsGamesResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsOverviewResponseDto
import com.wordonline.matching.auth.service.UserId
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.auth.service.UserStatisticsService
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.quest.dto.QuestCheckResponseDto
import com.wordonline.matching.quest.service.QuestService
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/users")
@RestController
class UserController(
    private val userService: UserService,
    private val userStatisticsService: UserStatisticsService,
    private val gameMatchService: LegacyGameMatchService,
    private val questService: QuestService,
) {
    @GetMapping("/mine")
    suspend fun getUser(@UserId userId: Long?): UserResponseDto =
        userService.getUser(userId!!).awaitSingle()

    @DeleteMapping("/mine")
    suspend fun deleteUser(@UserId userId: Long?): ResponseEntity<String> =
        try {
            userService.deleteUser(userId!!).awaitSingleOrNull()
            ResponseEntity.ok("successfully delete")
        } catch (e: Exception) {
            ResponseEntity(HttpStatus.NOT_FOUND)
        }

    @GetMapping("/mine/status")
    suspend fun getMyStatus(@UserId userId: Long?): Map<String, String> =
        mapOf("status" to userService.getStatus(userId!!).awaitSingle().name)

    @GetMapping("/mine/statistics/overview")
    suspend fun getMyStatisticsOverview(@UserId userId: Long?): UserStatisticsOverviewResponseDto =
        userStatisticsService.getOverview(userId!!)

    @GetMapping("/mine/statistics/games")
    suspend fun getMyStatisticsGames(
        @UserId userId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): UserStatisticsGamesResponseDto =
        userStatisticsService.getGames(userId!!, page, size)

    @GetMapping("/mine/match-info")
    suspend fun getMatchInfo(@UserId userId: Long?): MatchedInfoDto =
        gameMatchService.getMatchInfo(userId!!)

    @PostMapping("/mine/quests/check")
    suspend fun checkMyQuests(@UserId userId: Long?): QuestCheckResponseDto =
        QuestCheckResponseDto(questService.checkQuestsWithRewards(userId!!).awaitSingle())
}
