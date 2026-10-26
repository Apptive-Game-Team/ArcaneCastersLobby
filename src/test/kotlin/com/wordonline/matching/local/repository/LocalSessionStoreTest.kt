package com.wordonline.matching.local.repository

import com.wordonline.matching.local.config.LocalSessionProperties
import com.wordonline.matching.local.domain.LocalSessionRecord
import com.wordonline.matching.local.domain.LocalSessionState
import com.wordonline.matching.local.domain.LocalSessionType
import com.wordonline.matching.matching.repository.RedisScriptSandbox
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/** Runs the real transition script in [RedisScriptSandbox]; a mocked template would skip the branch under test. */
class LocalSessionStoreTest {

    private val sandbox = RedisScriptSandbox()
    private val properties = LocalSessionProperties(sessionTtl = Duration.ofHours(2))
    private val store = sandboxStore()

    private val record = LocalSessionRecord(
        sessionId = "local-7",
        userId = 5,
        type = LocalSessionType.PRACTICE,
        scenarioId = null,
        rightUserId = -54,
        hospitalityBot = true,
        issuedAt = Instant.parse("2026-10-12T03:00:00Z"),
        state = LocalSessionState.ISSUED,
    )

    @Test
    fun `저장한 세션은 TTL 2시간으로 남고 그대로 읽힌다`() = runBlocking<Unit> {
        store.save(record)

        assertThat(sandbox.ttlMillis("local-session:local-7")).isEqualTo(Duration.ofHours(2).toMillis())
        assertThat(store.find("local-7")).isEqualTo(record)
    }

    @Test
    fun `ISSUED 에서 REPORTED 로 한 번만 바뀌고 남은 TTL 과 다른 필드는 그대로다`() = runBlocking<Unit> {
        store.save(record)

        assertThat(store.transition("local-7", LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .isEqualTo(LocalSessionTransition.TRANSITIONED)
        assertThat(store.transition("local-7", LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .isEqualTo(LocalSessionTransition.STATE_MISMATCH)

        assertThat(store.find("local-7")).isEqualTo(record.copy(state = LocalSessionState.REPORTED))
        assertThat(sandbox.ttlMillis("local-session:local-7")).isEqualTo(Duration.ofHours(2).toMillis())
    }

    @Test
    fun `없는 세션의 전이는 MISSING 이다`() = runBlocking<Unit> {
        assertThat(store.transition("local-404", LocalSessionState.ISSUED, LocalSessionState.REPORTED))
            .isEqualTo(LocalSessionTransition.MISSING)
        assertThat(sandbox.exists("local-session:local-404")).isFalse()
    }

    private fun sandboxStore(): LocalSessionStore {
        val template: ReactiveStringRedisTemplate = mock()
        val valueOps: ReactiveValueOperations<String, String> = mock()
        whenever(template.opsForValue()).thenReturn(valueOps)
        whenever(template.execute(any<RedisScript<Any>>(), any<List<String>>(), any<List<Any>>()))
            .thenAnswer { invocation ->
                val script = invocation.getArgument<RedisScript<*>>(0)
                val result = sandbox.eval(
                    script.scriptAsString,
                    invocation.getArgument(1),
                    invocation.getArgument<List<*>>(2).map { it.toString() },
                )
                Flux.fromIterable(listOfNotNull(result))
            }
        whenever(valueOps.set(any<String>(), any<String>(), any<Duration>())).thenAnswer { invocation ->
            sandbox.set(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument<Duration>(2).toMillis())
            Mono.just(true)
        }
        whenever(valueOps.get(any<String>())).thenAnswer { invocation ->
            Mono.justOrEmpty(sandbox.get(invocation.getArgument<Any>(0).toString()))
        }
        return LocalSessionStore(template, Jackson2ObjectMapperBuilder.json().build(), properties)
    }
}
