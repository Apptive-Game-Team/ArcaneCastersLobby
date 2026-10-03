package com.wordonline.matching.quest.entity

import com.wordonline.matching.quest.reward.Reward
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * One row of `quest_rewards`. A quest can carry several. [rewardType] picks the
 * [com.wordonline.matching.quest.reward.RewardGrantor] that grants it, and [targetId] is what that
 * grantor grants (a magic id, a decoration id, an appearance id, a chest id), or null for a type
 * that needs no target.
 */
@Table("quest_rewards")
data class QuestReward(
    @Id val id: Long,
    val questId: Long,
    override val rewardType: String,
    override val targetId: Long? = null,
    override val amount: Int,
) : Reward {
    override fun describeRow(): String = "quest_rewards row $id (quest $questId)"
}
