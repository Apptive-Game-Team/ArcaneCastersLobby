package com.wordonline.matching.auth.service

import com.wordonline.matching.auth.dto.UserGameRecordResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsGamesResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsOverviewResponseDto
import com.wordonline.matching.auth.repository.UserGameSessionRecord
import com.wordonline.matching.auth.repository.UserStatisticsRepository
import com.wordonline.matching.matching.client.AccountClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.ceil

@Service
class UserStatisticsService(
    private val userStatisticsRepository: UserStatisticsRepository,
    private val accountClient: AccountClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun getOverview(userId: Long): UserStatisticsOverviewResponseDto {
        val counts = userStatisticsRepository.countOverview(userId)
        return UserStatisticsOverviewResponseDto(
            totalGameNum = counts.win + counts.lose + counts.draw,
            totalWinNum = counts.win,
            totalLoseNum = counts.lose,
            totalDrawNum = counts.draw,
        )
    }

    suspend fun getGames(userId: Long, page: Int, size: Int): UserStatisticsGamesResponseDto {
        val pageNumber = page.coerceAtLeast(0)
        val pageSize = size.coerceAtLeast(1)

        val totalGames = userStatisticsRepository.countGames(userId)
        val records = userStatisticsRepository.findGames(userId, limit = pageSize, offset = pageNumber.toLong() * pageSize)
        val opponentNames = resolveOpponentNames(records.map(UserGameSessionRecord::opponentId).distinct())

        val games = records.map { record ->
            UserGameRecordResponseDto(
                opponentId = record.opponentId,
                opponentName = opponentNames[record.opponentId],
                result = record.result,
                gameType = record.gameType,
                playedAt = record.playedAt,
            )
        }
        val totalPages = if (totalGames == 0L) 0 else ceil(totalGames.toDouble() / pageSize).toInt()

        return UserStatisticsGamesResponseDto(
            games = games,
            page = pageNumber,
            size = pageSize,
            totalPages = totalPages,
            last = pageNumber + 1 >= totalPages,
        )
    }

    /**
     * Resolves each distinct opponent id at most once per page, concurrently, so a page full of
     * the same bot opponent does not repeat account-server calls. A single lookup failure must
     * not fail the whole page (issue #27), so a failed opponent is simply left out of the map and
     * [UserGameRecordResponseDto.opponentName] falls back to null.
     */
    private suspend fun resolveOpponentNames(opponentIds: List<Long>): Map<Long, String> = coroutineScope {
        opponentIds
            .map { opponentId ->
                opponentId to async {
                    try {
                        accountClient.getMemberSuspend(opponentId).name
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Failed to resolve opponent name for opponentId={}", opponentId, e)
                        null
                    }
                }
            }
            .mapNotNull { (opponentId, deferredName) -> deferredName.await()?.let { opponentId to it } }
            .toMap()
    }
}
