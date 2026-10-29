package com.wordonline.matching.progression

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.transaction.reactive.TransactionalOperator

class ProgressionRewardsTest {
    private val repository = mock<ProgressionRepository>()
    private val service = ProgressionService(repository, ProgressionProperties(), mock<TransactionalOperator>())

    @Test
    fun `세력 선택 전 조회는 지급과 계정 생성을 수행하지 않는다`() = runTest {
        whenever(repository.faction(7)).thenReturn(null)
        assertThat(service.rewards(7)).isNull()
        verify(repository).faction(7)
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `시작 카드와 모든 레벨의 상자를 실제 지급 정책에서 구성한다`() = runTest {
        for (faction in listOf("HELL", "NATURE")) {
            whenever(repository.faction(7)).thenReturn(faction)
            val catalog = (1..5).map { ProgressionCatalogMagic(1, it.toLong(), "starter$it") } +
                (5..50 step 5).map { ProgressionCatalogMagic(it, (100 + it).toLong(), "$faction-$it") }
            whenever(repository.rewardCatalog(faction)).thenReturn(catalog)
            val result = service.rewards(7)!!
            assertThat(result.faction).isEqualTo(faction)
            assertThat(result.levels.map { it.level }).containsExactlyElementsOf(1..50)
            assertThat(result.levels.first().cards.sumOf { it.amount }).isEqualTo(15)
            assertThat(result.levels.first().chestCount).isZero()
            assertThat(result.levels.sumOf { it.chestCount }).isEqualTo(49)
            assertThat(result.levels.sumOf { it.rewardSlots }).isEqualTo(178)
            assertThat(result.levels.sumOf { l -> l.cards.sumOf { it.amount } }).isEqualTo(35)
            assertThat(result.levels.last().requiredXp).isEqualTo(12500)
            assertThat(result.levels.last().rewardSlots).isEqualTo(7)
            assertThat(result.levels[9].cards.single().magicName).isEqualTo("$faction-10")
        }
        verify(repository, never()).lock(any())
        verify(repository, never()).grantMagic(any(), any(), any())
        verify(repository, never()).issueChest(any(), any(), any())
    }
}
