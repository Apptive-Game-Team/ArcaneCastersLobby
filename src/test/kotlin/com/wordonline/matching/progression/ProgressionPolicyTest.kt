package com.wordonline.matching.progression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ProgressionPolicyTest {
    @Test
    fun `레벨 경계와 전체 상자 추첨 수량`() {
        assertThat(ProgressionPolicy.threshold(50)).isEqualTo(12500)
        assertThat(ProgressionPolicy.level(59)).isEqualTo(1)
        assertThat(ProgressionPolicy.level(60)).isEqualTo(2)
        assertThat(ProgressionPolicy.cost(9)).isEqualTo(140)
        assertThat(ProgressionPolicy.cost(10)).isEqualTo(150)
        assertThat(ProgressionPolicy.cost(49)).isEqualTo(430)
        assertThat((1..49).map(ProgressionPolicy::cost).zipWithNext().all { (a, b) -> a < b }).isTrue()
        assertThat(ProgressionPolicy.level(899)).isEqualTo(9)
        assertThat(ProgressionPolicy.level(900)).isEqualTo(10)
        assertThat(ProgressionPolicy.level(12499)).isEqualTo(49)
        assertThat(ProgressionPolicy.level(12500)).isEqualTo(50)
        assertThat(ProgressionPolicy.level(Long.MAX_VALUE)).isEqualTo(50)
        assertThat((2..50).sumOf(ProgressionPolicy::slots)).isEqualTo(178)
    }

    @Test
    fun `승리 일반 패배 무승부 취소와 항복 경험치`() {
        assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", null, 180)).isEqualTo(100)
        assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", null, 180)).isEqualTo(25)
        assertThat(ProgressionPolicy.xp(1, null, null, "DRAW", null, 180)).isEqualTo(50)
        assertThat(ProgressionPolicy.xp(1, null, null, "ABANDONED", null, 180)).isZero()
        assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", "SURRENDER", 180)).isZero()
        assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", "SURRENDER", 180)).isEqualTo(100)
    }

    @Test
    fun `오류와 연결 끊김 종료는 30초부터 기존 XP 정책을 적용한다`() {
        for (reason in listOf("LOOP_FAILURE", "DISCONNECT")) {
            for (seconds in listOf<Long?>(null, -1, 0, 29)) {
                assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", reason, seconds)).isZero()
                assertThat(ProgressionPolicy.xp(1, null, null, "DRAW", reason, seconds)).isZero()
            }
            for (seconds in listOf(30L, 31L)) {
                assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", reason, seconds)).isEqualTo(100)
                assertThat(ProgressionPolicy.xp(1, null, null, "DRAW", reason, seconds)).isEqualTo(50)
                assertThat(ProgressionPolicy.xp(1, null, null, "ABANDONED", reason, seconds)).isZero()
            }
        }
        assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", "DISCONNECT", 30)).isZero()
    }

    @Test
    fun `정상 종료와 항복 보상에는 최소 시간을 적용하지 않는다`() {
        for (seconds in listOf<Long?>(null, 0, 29, 30)) {
            assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", null, seconds)).isEqualTo(100)
            assertThat(ProgressionPolicy.xp(1, 2, 1, "COMPLETED", null, seconds)).isEqualTo(25)
            assertThat(ProgressionPolicy.xp(1, null, null, "DRAW", null, seconds)).isEqualTo(50)
            assertThat(ProgressionPolicy.xp(1, 1, 2, "COMPLETED", "SURRENDER", seconds)).isEqualTo(100)
        }
    }

    @Test
    fun `풀이 비어 있으면 카드 확률이 100퍼센트여도 재화를 준다`() {
        assertThat(ProgressionPolicy.drawCard(false, ProgressionProperties(cardProbability = 1.0), Random(1))).isFalse()
        assertThat(ProgressionPolicy.drawCard(true, ProgressionProperties(cardProbability = 1.0), Random(1))).isTrue()
        assertThat(ProgressionPolicy.drawCard(true, ProgressionProperties(cardProbability = 0.0), Random(1))).isFalse()
    }
}
