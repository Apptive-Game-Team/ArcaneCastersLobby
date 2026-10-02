package com.wordonline.matching.appearance.service

import com.wordonline.matching.appearance.dto.AppearanceResponseDto
import com.wordonline.matching.appearance.repository.AppearanceRepository
import org.springframework.stereotype.Service

/** Outcome of selecting an appearance. */
sealed interface AppearanceSelection {
    /** `users.appearance` now holds [key]. */
    data class Selected(val key: String) : AppearanceSelection

    /** No appearance has this key. */
    data object UnknownKey : AppearanceSelection

    /** The appearance exists but this user does not own it; `users.appearance` is unchanged. */
    data object NotOwned : AppearanceSelection

    /** The user owns the appearance but has no `users` row to write it to. */
    data object UserNotFound : AppearanceSelection
}

/**
 * The appearances a user owns and the one they have selected. `users.appearance` stays the single
 * source of truth for the selection, which the game server and the client read.
 */
@Service
class AppearanceService(
    private val appearanceRepository: AppearanceRepository,
) {
    /** Read-only. The whole catalog, ordered by `sort_order`, marked owned and selected for this user. */
    suspend fun findMyAppearances(userId: Long): List<AppearanceResponseDto> =
        appearanceRepository.findCatalogForUser(userId).map {
            AppearanceResponseDto(it.key, it.sortOrder, it.owned, it.selected)
        }

    /**
     * Selects [key] when this user owns it. The ownership check and the write are one `UPDATE`
     * ([AppearanceRepository.selectIfOwned]), so no concurrent change can leave an unowned key
     * selected. Only when that changes nothing does a second read find out why.
     */
    suspend fun selectAppearance(userId: Long, key: String): AppearanceSelection {
        if (appearanceRepository.selectIfOwned(userId, key) == 1L) {
            return AppearanceSelection.Selected(key)
        }
        val ownership = appearanceRepository.findKeyOwnership(userId, key)
        return when {
            ownership == null -> AppearanceSelection.UnknownKey
            !ownership.owned -> AppearanceSelection.NotOwned
            else -> AppearanceSelection.UserNotFound
        }
    }
}
