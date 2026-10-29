package com.wordonline.matching.progression

data class ProgressionCatalogMagic(val level: Int, val magicId: Long, val magicName: String)
data class ProgressionFixedReward(val magicId: Long, val magicName: String, val amount: Int)
data class ProgressionLevelReward(
    val level: Int, val requiredXp: Long, val chestCount: Int, val rewardSlots: Int,
    val cards: List<ProgressionFixedReward>,
)
data class ProgressionRewardsResponse(val faction: String, val levels: List<ProgressionLevelReward>)
