package com.wordonline.matching.progression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ProgressionPolicyTest {
    @Test
    fun `레벨 경계와 전체 상자 추첨 수량`() {
        assertThat(ProgressionPolicy.threshold(50)).isEqualTo(12500)
        assertThat(ProgressionPolicy.level(99)).isEqualTo(1)
        assertThat(ProgressionPolicy.level(100)).isEqualTo(2)
        assertThat(ProgressionPolicy.level(899)).isEqualTo(9)
        assertThat(ProgressionPolicy.level(900)).isEqualTo(10)
        assertThat(ProgressionPolicy.level(12499)).isEqualTo(49)
        assertThat(ProgressionPolicy.level(12500)).isEqualTo(50)
        assertThat(ProgressionPolicy.level(Long.MAX_VALUE)).isEqualTo(50)
        assertThat((2..50).sumOf(ProgressionPolicy::slots)).isEqualTo(178)
    }

    @Test
    fun `승리 일반 패배 무승부 취소와 항복 경험치`() {
        assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", null)).isEqualTo(100)
        assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", null)).isEqualTo(25)
        assertThat(ProgressionPolicy.xp(1, null, null, "DRAW", null)).isEqualTo(50)
        assertThat(ProgressionPolicy.xp(1, null, null, "ABANDONED", null)).isZero()
        assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", "SURRENDER")).isZero()
        assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", "SURRENDER")).isEqualTo(100)
    }

    @Test
    fun `풀이 비어 있으면 카드 확률이 100퍼센트여도 재화를 준다`() {
        assertThat(ProgressionPolicy.drawCard(false, ProgressionProperties(cardProbability = 1.0), Random(1))).isFalse()
        assertThat(ProgressionPolicy.drawCard(true, ProgressionProperties(cardProbability = 1.0), Random(1))).isTrue()
        assertThat(ProgressionPolicy.drawCard(true, ProgressionProperties(cardProbability = 0.0), Random(1))).isFalse()
    }
}
