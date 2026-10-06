package com.wordonline.matching.friend.repository

import com.wordonline.matching.friend.domain.FriendRequest
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface FriendRequestRepository : CoroutineCrudRepository<FriendRequest, Long> {

    @Query("SELECT * FROM friend_requests WHERE receiver_id = :receiverId AND status = 'PENDING' ORDER BY created_at DESC")
    fun findPendingByReceiverId(receiverId: Long): Flow<FriendRequest>

    @Query("SELECT * FROM friend_requests WHERE sender_id = :senderId AND status = 'PENDING' ORDER BY created_at DESC")
    fun findPendingBySenderId(senderId: Long): Flow<FriendRequest>

    @Query("SELECT * FROM friend_requests WHERE sender_id = :senderId AND receiver_id = :receiverId AND status = 'PENDING' LIMIT 1")
    suspend fun findPending(senderId: Long, receiverId: Long): FriendRequest?

    @Query("SELECT * FROM friend_requests WHERE ((sender_id = :userA AND receiver_id = :userB) OR (sender_id = :userB AND receiver_id = :userA)) AND status = 'PENDING' LIMIT 1")
    suspend fun findPendingBetween(userA: Long, userB: Long): FriendRequest?

    @Modifying
    @Query("UPDATE friend_requests SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String): Long
}
