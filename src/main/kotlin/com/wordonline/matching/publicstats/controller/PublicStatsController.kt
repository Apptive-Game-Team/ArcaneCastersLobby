package com.wordonline.matching.publicstats.controller

import com.wordonline.matching.publicstats.dto.PublicMagicStatisticsResponse
import com.wordonline.matching.publicstats.dto.PublicRankingResponse
import com.wordonline.matching.publicstats.service.PublicStatsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Unauthenticated read endpoints for the website. Responses hold no email and no user id. */
@RestController
@RequestMapping("/api/public")
class PublicStatsController(
    private val publicStatsService: PublicStatsService,
) {
    @GetMapping("/rankings")
    suspend fun getRankings(): PublicRankingResponse = publicStatsService.getRanking()

    @GetMapping("/statistics/magics")
    suspend fun getMagicStatistics(): PublicMagicStatisticsResponse = publicStatsService.getMagicStatistics()
}
