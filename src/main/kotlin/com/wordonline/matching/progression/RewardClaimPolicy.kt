package com.wordonline.matching.progression

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties("rewards")
data class RewardsProperties(val manualClaimsEnabled: Boolean = false)

@Component
class RewardClaimPolicy(private val properties: RewardsProperties = RewardsProperties()) {
    fun manual(capability: String?): Boolean = properties.manualClaimsEnabled && capability == "manual-v1"
}

data class ProgressionClaimReward(
    val rewardType: String, val rewardId: Long, val rewardKey: String,
    val amount: Int, val rewardSlots: Int = 0,
)

data class PendingProgressionReward(
    val id: Long, val source: String, val level: Int?, val createdAt: java.time.Instant,
    val rewards: List<ProgressionClaimReward>,
)

data class PendingProgressionRewardsResponse(
    val enabled: Boolean, val baselineLevel: Int, val claimedLevels: List<Int>,
    val rewards: List<PendingProgressionReward>,
)

data class ProgressionClaimResponse(
    val id: Long, val alreadyClaimed: Boolean, val rewards: List<ProgressionClaimReward>,
)
