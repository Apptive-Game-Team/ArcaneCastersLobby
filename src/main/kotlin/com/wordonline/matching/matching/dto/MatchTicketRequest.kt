package com.wordonline.matching.matching.dto

enum class MatchDeckMode {
    SELECTED,
    RANDOM,
}

data class MatchTicketRequest(
    val deckMode: MatchDeckMode = MatchDeckMode.SELECTED,
)
