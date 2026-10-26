package com.wordonline.matching.friend.service

import com.wordonline.matching.auth.service.UserService
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.CancellationException
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class FriendPresenceService(
    private val redis: ReactiveStringRedisTemplate,
    private val userService: UserService,
) {
    suspend fun heartbeat(userId: Long) {
        redis.opsForValue().set(key(userId), "1", Duration.ofSeconds(35)).awaitSingle()
    }

    suspend fun status(userId: Long): String = try {
        if (!redis.hasKey(key(userId)).awaitSingle()) "Offline"
        else userService.getStatus(userId).awaitSingleOrNull()?.name ?: "Offline"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 접속 정보를 확인하지 못한 경우 온라인이나 오프라인으로 단정하지 않는다.
        "Unknown"
    }

    private fun key(userId: Long) = "friends:presence:$userId"
}
