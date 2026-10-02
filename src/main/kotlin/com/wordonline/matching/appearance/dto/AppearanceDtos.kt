package com.wordonline.matching.appearance.dto

/**
 * One element of `GET /api/users/mine/appearances`: the whole catalog, ordered by `sort_order`.
 * [key] is the client's directory name under `Resources/PlayerAppearances/`. `default` is always
 * [owned]; [selected] is true for the one equal to `users.appearance`.
 */
data class AppearanceResponseDto(
    val key: String,
    val sortOrder: Int,
    val owned: Boolean,
    val selected: Boolean,
)

/**
 * Body of `PUT /api/users/mine/appearance`. Nullable so a missing field reaches the controller and
 * answers 400 instead of failing in JSON decoding.
 */
data class AppearanceSelectRequestDto(
    val appearance: String? = null,
)

/** Response of `PUT /api/users/mine/appearance`: the appearance now selected. */
data class AppearanceSelectResponseDto(
    val appearance: String,
)
