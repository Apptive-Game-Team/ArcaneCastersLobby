package com.wordonline.matching.friend.service

import com.wordonline.matching.auth.domain.UserStatus
import com.wordonline.matching.auth.service.UserService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import reactor.core.publisher.Mono
import java.time.Duration

class FriendPresenceServiceTest {
    private val redis: ReactiveStringRedisTemplate = mock()
    private val users: UserService = mock()
    private val values: ReactiveValueOperations<String, String> = mock()
    private val service = FriendPresenceService(redis, users)

    @Test
    fun `heartbeat가 없으면 기존 Online 상태를 조회하지 않고 Offline을 반환한다`(): Unit = runTest {
        whenever(redis.hasKey("friends:presence:1")).thenReturn(Mono.just(false))
        assertThat(service.status(1)).isEqualTo("Offline")
        verifyNoInteractions(users)
    }

    @Test
    fun `접속 중인 플레이어의 게임 상태를 보존한다`(): Unit = runTest {
        whenever(redis.hasKey("friends:presence:1")).thenReturn(Mono.just(true))
        whenever(users.getStatus(1)).thenReturn(Mono.just(UserStatus.OnPlaying))
        assertThat(service.status(1)).isEqualTo("OnPlaying")
    }

    @Test
    fun `접속 저장소 장애는 오프라인과 구분한다`(): Unit = runTest {
        whenever(redis.hasKey("friends:presence:1")).thenReturn(Mono.error(IllegalStateException("unavailable")))
        assertThat(service.status(1)).isEqualTo("Unknown")
    }

    @Test
    fun `heartbeat는 만료 시간을 함께 갱신한다`(): Unit = runTest {
        whenever(redis.opsForValue()).thenReturn(values)
        whenever(values.set("friends:presence:1", "1", Duration.ofSeconds(35))).thenReturn(Mono.just(true))
        service.heartbeat(1)
        verify(values).set("friends:presence:1", "1", Duration.ofSeconds(35))
    }
}
