package com.wordonline.matching.quest.domain

/**
 * State of one quest for one user.
 *
 * In the `user_quests` table only [IN_PROGRESS] and [COMPLETED] are written: a row starts at
 * [IN_PROGRESS], and the check path (for an `AUTO` quest) or the claim endpoint moves it to
 * [COMPLETED] in the same transaction that grants the rewards. [PENDING] is never stored by this server; the read endpoints report it for a quest with
 * no progress yet (see [QuestState.derive]).
 */
enum class QuestState {
    PENDING, IN_PROGRESS, COMPLETED;

    companion object {
        /**
         * The state the read endpoints report. [COMPLETED] means the rewards were granted, which
         * only the check path and the claim endpoint do, so a quest whose condition is met but not
         * yet claimed still reads as [IN_PROGRESS] until the next check or the explicit claim.
         */
        fun derive(storedState: QuestState?, progress: Int): QuestState = when {
            storedState == COMPLETED -> COMPLETED
            progress > 0 -> IN_PROGRESS
            else -> PENDING
        }
    }
}
