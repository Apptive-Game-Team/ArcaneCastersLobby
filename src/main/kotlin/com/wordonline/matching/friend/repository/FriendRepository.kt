package com.wordonline.matching.friend.repository

import com.wordonline.matching.friend.domain.Friendship
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FriendRepository : CoroutineCrudRepository<Friendship, Long> {

    @Query("SELECT friend_id FROM friendships WHERE user_id = :userId ORDER BY created_at DESC")
    fun findFriendIdsByUserId(userId: Long): Flow<Long>

    suspend fun existsByUserIdAndFriendId(userId: Long, friendId: Long): Boolean

    @Modifying
    @Query("INSERT INTO friendships (user_id, friend_id, created_at) VALUES (:userId, :friendId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING")
    suspend fun insertFriendship(userId: Long, friendId: Long): Long

    @Modifying
    @Query("DELETE FROM friendships WHERE (user_id = :userId AND friend_id = :friendId) OR (user_id = :friendId AND friend_id = :userId)")
    suspend fun deleteFriendship(userId: Long, friendId: Long): Long
}
