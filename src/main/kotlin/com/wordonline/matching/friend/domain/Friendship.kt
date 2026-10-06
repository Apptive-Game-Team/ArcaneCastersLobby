package com.wordonline.matching.friend.domain

import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("friendships")
data class Friendship(
    val userId: Long,
    val friendId: Long,
    val createdAt: Instant = Instant.now(),
)
