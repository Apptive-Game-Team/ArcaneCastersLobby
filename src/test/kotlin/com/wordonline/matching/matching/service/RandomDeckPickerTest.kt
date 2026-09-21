package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.repository.RandomDeckCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RandomDeckPickerTest {

    @Test
    fun `가진 마법에서 15장을 뽑는다`() {
        val decks = pickWithManySeeds(spawn(1, 3), spawn(2, 3), shot(3, 8), explosion(4, 3), drop(5, 3))

        assertThat(decks).allSatisfy { deck -> assertThat(deck).hasSize(15) }
    }

    @Test
    fun `한 마법도 가진 장수를 넘지 않는다`() {
        val candidates = listOf(spawn(1, 4), shot(2, 6), drop(3, 5), summon(4, 5))
        val owned = candidates.associate { it.magicId to it.ownedCount }

        val decks = pickWithManySeeds(*candidates.toTypedArray())

        assertThat(decks).allSatisfy { deck ->
            assertThat(deck.groupingBy { it }.eachCount()).allSatisfy { magicId, taken ->
                assertThat(taken).isLessThanOrEqualTo(owned.getValue(magicId))
            }
        }
    }

    @Test
    fun `소환수를 내놓는 마법이 3장 이상 들어간다`() {
        val decks = pickWithManySeeds(spawn(1, 2), spawn(2, 2), shot(3, 20))

        assertThat(decks).allSatisfy { deck ->
            assertThat(deck.count { it == 1L || it == 2L }).isGreaterThanOrEqualTo(3)
        }
    }

    @Test
    fun `가진 소환 마법이 3장 미만이면 뽑지 못한다`() {
        val result = RandomDeckPicker.pick(listOf(spawn(1, 2), shot(2, 20)), Random(1))

        assertThat(result).isEqualTo(RandomDeckPickResult.NotEnoughSpawnCards(2))
    }

    // 'Summon' 은 totem 이나 tower 같은 구조물이라 소환수로 치지 않는다.
    @Test
    fun `구조물을 세우는 Summon 마법은 소환수로 세지 않는다`() {
        val result = RandomDeckPicker.pick(listOf(summon(1, 10), shot(2, 10)), Random(1))

        assertThat(result).isEqualTo(RandomDeckPickResult.NotEnoughSpawnCards(0))
    }

    @Test
    fun `가진 카드가 15장 미만이면 뽑지 못한다`() {
        val result = RandomDeckPicker.pick(listOf(spawn(1, 5), shot(2, 9)), Random(1))

        assertThat(result).isEqualTo(RandomDeckPickResult.NotEnoughCards(14))
    }

    @Test
    fun `가진 장수가 넉넉하면 같은 마법이 3장을 넘어도 된다`() {
        val decks = pickWithManySeeds(spawn(1, 3), shot(2, 12))

        assertThat(decks).allSatisfy { deck -> assertThat(deck.count { it == 2L }).isEqualTo(12) }
    }

    @Test
    fun `가진 장수가 0 인 마법은 후보에서 빠진다`() {
        val decks = pickWithManySeeds(spawn(1, 3), shot(2, 12), drop(3, 0))

        assertThat(decks).allSatisfy { deck -> assertThat(deck).doesNotContain(3L) }
    }

    // 뽑기가 seed 하나에 매달리지 않는지 보려고 같은 후보로 seed 를 여러 개 돌린다.
    private fun pickWithManySeeds(vararg candidates: RandomDeckCandidate): List<List<Long>> =
        (1..50).map { seed ->
            val result = RandomDeckPicker.pick(candidates.toList(), Random(seed))
            assertThat(result).isInstanceOf(RandomDeckPickResult.Picked::class.java)
            (result as RandomDeckPickResult.Picked).cardIds
        }

    private fun spawn(magicId: Long, ownedCount: Int) = RandomDeckCandidate(magicId, ownedCount, "Spawn")

    private fun summon(magicId: Long, ownedCount: Int) = RandomDeckCandidate(magicId, ownedCount, "Summon")

    private fun shot(magicId: Long, ownedCount: Int) = RandomDeckCandidate(magicId, ownedCount, "Shot")

    private fun drop(magicId: Long, ownedCount: Int) = RandomDeckCandidate(magicId, ownedCount, "Drop")

    private fun explosion(magicId: Long, ownedCount: Int) = RandomDeckCandidate(magicId, ownedCount, "Explosion")
}
