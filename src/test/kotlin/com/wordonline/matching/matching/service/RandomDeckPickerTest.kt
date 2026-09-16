package com.wordonline.matching.matching.service

import com.wordonline.matching.deck.dto.MyCardListRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RandomDeckPickerTest {
    @Test
    fun `creates a valid 15-card deck from owned magics`() {
        val cards = listOf(
            magic(1, "FIRE", 8),
            magic(2, "FIRE", 3),
            magic(3, "WATER", 8),
            magic(4, "EARTH", 3),
            magic(5, "WIND", 3),
        )

        val deck = RandomDeckPicker.pick(cards, Random(42))!!

        assertThat(deck).hasSize(15)
        assertThat(deck.groupingBy { it }.eachCount().values).allMatch { it <= 3 }
        val elements = deck.map { id -> cards.single { it.id() == id }.element() }.toSet()
        assertThat(elements).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `rejects a pool whose capped copy count is below 15`() {
        val cards = listOf(magic(1, "FIRE", 99), magic(2, "WATER", 2))

        assertThat(RandomDeckPicker.pick(cards, Random(1))).isNull()
    }

    @Test
    fun `rejects a pool with only one element`() {
        val cards = (1L..5L).map { magic(it, "FIRE", 3) }

        assertThat(RandomDeckPicker.pick(cards, Random(1))).isNull()
    }

    private fun magic(id: Long, element: String, count: Int) =
        MyCardListRow(id, "magic-$id", element, 1, count, true, null, null)
}
