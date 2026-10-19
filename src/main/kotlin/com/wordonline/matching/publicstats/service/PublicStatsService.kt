package com.wordonline.matching.publicstats.service

import com.wordonline.matching.matching.client.AccountClient
import com.wordonline.matching.publicstats.PublicStatsProperties
import com.wordonline.matching.publicstats.dto.PublicMagicStatisticDto
import com.wordonline.matching.publicstats.dto.PublicMagicStatisticsResponse
import com.wordonline.matching.publicstats.dto.PublicRankingPlayerDto
import com.wordonline.matching.publicstats.dto.PublicRankingResponse
import com.wordonline.matching.publicstats.repository.PublicStatsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds the public ranking and magic statistics and keeps each result in memory for
 * [PublicStatsProperties.cacheTtl], so website traffic reads the database and the account server at
 * most once per TTL no matter how many visitors there are. A failed computation is not cached.
 */
@Service
class PublicStatsService(
    private val repository: PublicStatsRepository,
    private val accountClient: AccountClient,
    private val properties: PublicStatsProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val rankingCache = TtlCache<PublicRankingResponse>()
    private val magicCache = TtlCache<PublicMagicStatisticsResponse>()

    suspend fun getRanking(): PublicRankingResponse = rankingCache.get { computeRanking() }

    suspend fun getMagicStatistics(): PublicMagicStatisticsResponse = magicCache.get { computeMagicStatistics() }

    private suspend fun computeRanking(): PublicRankingResponse {
        val now = now()
        val candidates = repository.findRankingCandidates(
            minGames = properties.rankingMinGames,
            limit = properties.rankingSize * properties.rankingCandidateFactor,
        )
        val names = coroutineScope {
            candidates.map { candidate -> async { lookUpPublicName(candidate.userId) } }.awaitAll()
        }
        val players = candidates.zip(names)
            .mapNotNull { (candidate, name) -> name?.let { candidate to it } }
            .take(properties.rankingSize)
            .mapIndexed { index, (candidate, name) ->
                PublicRankingPlayerDto(
                    rank = index + 1,
                    name = name,
                    mmr = candidate.mmr,
                    wins = candidate.wins,
                    losses = candidate.losses,
                    draws = candidate.draws,
                )
            }
        return PublicRankingResponse(updatedAt = now, players = players)
    }

    /**
     * The member's name, or `null` when the player must not be listed: the account server cannot
     * supply it (deleted account, outage) or the account is a guest.
     */
    private suspend fun lookUpPublicName(userId: Long): String? =
        try {
            accountClient.getMemberSuspend(userId).takeUnless { it.guest }?.name
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[Public Stats] skipping user {} in ranking, member lookup failed: {}", userId, e.message)
            null
        }

    private suspend fun computeMagicStatistics(): PublicMagicStatisticsResponse {
        val to = now()
        val from = to.minus(properties.magicWindow)
        val totalGames = repository.countGames(from, to)
        val deckEntries = repository.countDeckEntries(from, to)
        val figures = repository.findMagicDeckFigures(from, to)
        val casts = repository.sumCastsByMagic(from, to)
        val magics = figures.map {
            PublicMagicStatisticDto(
                magicId = it.magicId,
                name = it.name,
                prefab = it.prefab,
                decks = it.decks,
                pickRate = if (deckEntries == 0L) 0.0 else it.decks.toDouble() / deckEntries,
                winRate = if (it.winOutcomeDecks < properties.magicMinSample) null
                else it.winOutcomeDecksWon.toDouble() / it.winOutcomeDecks,
                casts = casts[it.magicId] ?: 0L,
            )
        }.sortedByDescending { it.decks }
        return PublicMagicStatisticsResponse(updatedAt = to, from = from, to = to, totalGames = totalGames, magics = magics)
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    /** One computed value shared by every caller until [PublicStatsProperties.cacheTtl] has passed. */
    private inner class TtlCache<T : Any> {
        private val mutex = Mutex()

        @Volatile
        private var entry: Pair<Instant, T>? = null

        suspend fun get(compute: suspend () -> T): T {
            fresh()?.let { return it }
            return mutex.withLock {
                // Another caller may have refilled the cache while this one waited for the lock.
                fresh() ?: compute().also { entry = clock.instant() to it }
            }
        }

        private fun fresh(): T? = entry?.takeIf {
            Duration.between(it.first, clock.instant()) < properties.cacheTtl
        }?.second
    }
}
