package com.wordonline.matching.matching.service

import com.wordonline.matching.deck.dto.MyCardListRow
import com.wordonline.matching.deck.repository.CardListQueryRepository
import com.wordonline.matching.deck.validation.DeckValidator
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

@Service
class RandomDeckService(
    private val cardListQueryRepository: CardListQueryRepository,
    private val deckValidator: DeckValidator,
) {
    suspend fun create(userId: Long): List<Long> {
        val cards = cardListQueryRepository.findMyCardList(userId).collectList().awaitSingle()
        val deck = RandomDeckPicker.pick(cards)
            ?: throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Not enough eligible owned magics to create a random deck",
            )

        if (!deckValidator.isValid(userId, deck).awaitSingle()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Random deck is invalid")
        }
        return deck
    }
}

internal object RandomDeckPicker {
    fun pick(cards: List<MyCardListRow>, random: Random = Random.Default): List<Long>? {
        val eligible = cards
            .filter { it.unlocked() && it.count() > 0 }
            .map { OwnedMagic(it.id(), it.element(), minOf(it.count(), DeckValidator.MAX_NUM_OF_SAME_CARD)) }

        if (eligible.sumOf(OwnedMagic::count) < DeckValidator.DECK_CARD_COUNT) return null
        val elements = eligible.groupBy(OwnedMagic::element)
        if (elements.size < DeckValidator.LEAST_NUM_OF_ELEMENTS) return null

        val selected = mutableListOf<Long>()
        val remaining = eligible.associate { it.id to it.count }.toMutableMap()
        elements.keys.shuffled(random).take(DeckValidator.LEAST_NUM_OF_ELEMENTS).forEach { element ->
            val magic = elements.getValue(element).shuffled(random).first()
            selected += magic.id
            remaining[magic.id] = remaining.getValue(magic.id) - 1
        }

        val pool = remaining.flatMap { (magicId, count) -> List(count) { magicId } }.shuffled(random)
        val needed = DeckValidator.DECK_CARD_COUNT - selected.size
        if (pool.size < needed) return null
        selected += pool.take(needed)
        return selected.shuffled(random)
    }

    private data class OwnedMagic(val id: Long, val element: String, val count: Int)
}
