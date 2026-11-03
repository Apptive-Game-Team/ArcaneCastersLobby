package com.wordonline.matching.matching.domain

// 2026-10-27 운영 MMR로 배정한 상대다. 매 경기 변하는 MMR이나 행동 설정용 bot_tier로 재분류하지 않는다.
enum class PracticeDifficulty(vararg ids: Long) {
    INTRO(-37, -17, -18, -38, -52),
    BASIC(-22, -23, -24, -47, -51, -48, -49, -50, -19, -53, -33, -32, -34),
    SKILLED(-59, -58, -57, -20, -56, -40, -41, -39, -61, -25, -26, -55, -60, -21),
    ADVANCED(-27, -42, -29, -28, -43, -36, -54, -46),
    EXPERT(-35, -44, -31, -45, -30);

    val botIds: List<Long> = ids.toList()
}
