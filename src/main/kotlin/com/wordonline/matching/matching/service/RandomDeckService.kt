package com.wordonline.matching.matching.service

import com.wordonline.matching.deck.validation.DeckValidator
import com.wordonline.matching.matching.repository.RandomDeckCandidate
import com.wordonline.matching.matching.repository.RandomDeckCandidateRepository
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

@Service
class RandomDeckService(
    private val randomDeckCandidateRepository: RandomDeckCandidateRepository,
    private val deckValidator: DeckValidator,
) {
    suspend fun create(userId: Long): List<Long> {
        val candidates = randomDeckCandidateRepository.findOwnedMagics(userId).collectList().awaitSingle()
        val deck = when (val result = RandomDeckPicker.pick(candidates)) {
            is RandomDeckPickResult.Picked -> result.cardIds
            is RandomDeckPickResult.NotEnoughCards -> throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "A random deck needs ${DeckValidator.DECK_CARD_COUNT} owned cards, but this user owns ${result.ownedCards}",
            )
            is RandomDeckPickResult.NotEnoughSpawnCards -> throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "A random deck needs ${RandomDeckPicker.LEAST_NUM_OF_SPAWN_CARDS} cards that spawn a unit, " +
                    "but this user owns ${result.ownedSpawnCards}",
            )
        }

        if (!deckValidator.isValid(userId, deck).awaitSingle()) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Random deck is invalid")
        }
        return deck
    }
}

internal sealed interface RandomDeckPickResult {
    data class Picked(val cardIds: List<Long>) : RandomDeckPickResult

    data class NotEnoughCards(val ownedCards: Int) : RandomDeckPickResult

    data class NotEnoughSpawnCards(val ownedSpawnCards: Int) : RandomDeckPickResult
}

/**
 * 가진 마법에서 15장을 뽑는다.
 *
 * 생성 규칙은 세 가지다. 덱은 [DeckValidator.DECK_CARD_COUNT] 장이고, 한 마법은 가진 장수까지만
 * 넣으며, 유닛을 소환하는 마법이 [LEAST_NUM_OF_SPAWN_CARDS] 장 이상 들어간다. 이것은 랜덤 덱을
 * 만들 때만 거는 규칙이지 덱 legality 규칙이 아니다. [DeckValidator] 는 15장인지와 자기 카드인지만
 * 본다.
 */
internal object RandomDeckPicker {

    const val LEAST_NUM_OF_SPAWN_CARDS = 3

    // 'Summon' 은 totem 이나 tower 같은 구조물이고, 유닛을 내놓는 것은 'Spawn' 이다.
    private const val SPAWN_CAST_KIND = "Spawn"

    fun pick(candidates: List<RandomDeckCandidate>, random: Random = Random.Default): RandomDeckPickResult {
        val owned = candidates.filter { it.ownedCount > 0 }

        val ownedCards = owned.sumOf { it.ownedCount }
        if (ownedCards < DeckValidator.DECK_CARD_COUNT) return RandomDeckPickResult.NotEnoughCards(ownedCards)

        val (spawn, rest) = owned.partition { it.castKind == SPAWN_CAST_KIND }
        val ownedSpawnCards = spawn.sumOf { it.ownedCount }
        if (ownedSpawnCards < LEAST_NUM_OF_SPAWN_CARDS) {
            return RandomDeckPickResult.NotEnoughSpawnCards(ownedSpawnCards)
        }

        val spawnCopies = copies(spawn).shuffled(random)
        val deck = spawnCopies.take(LEAST_NUM_OF_SPAWN_CARDS)
        val remainingCopies = (spawnCopies.drop(LEAST_NUM_OF_SPAWN_CARDS) + copies(rest)).shuffled(random)
        return RandomDeckPickResult.Picked(
            (deck + remainingCopies.take(DeckValidator.DECK_CARD_COUNT - deck.size)).shuffled(random),
        )
    }

    // 한 덱에 15장보다 많이 들어갈 수는 없으니, 999장을 가진 마법도 후보 뭉치에는 15장만 넣는다.
    // 뽑을 수 있는 덱이 달라지지는 않고 뭉치 크기만 보유량과 무관하게 묶인다.
    private fun copies(candidates: List<RandomDeckCandidate>): List<Long> =
        candidates.flatMap { candidate ->
            List(minOf(candidate.ownedCount, DeckValidator.DECK_CARD_COUNT)) { candidate.magicId }
        }
}
