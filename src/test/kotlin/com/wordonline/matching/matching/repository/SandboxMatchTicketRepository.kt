package com.wordonline.matching.matching.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.config.MatchTicketProperties
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.ReactiveZSetOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * A [MatchTicketRepository] whose redis calls land in [RedisScriptSandbox].
 *
 * Going through the real repository rather than evaluating the script constants directly is
 * the point: the KEYS/ARGV ordering is the easiest thing to get wrong, and a test that
 * restated that ordering itself would stay green while production broke.
 */
fun sandboxMatchTicketRepository(
    sandbox: RedisScriptSandbox,
    properties: MatchTicketProperties = MatchTicketProperties(),
): MatchTicketRepository {
    val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()
    val template: ReactiveStringRedisTemplate = mock()
    val valueOps: ReactiveValueOperations<String, String> = mock()
    val zSetOps: ReactiveZSetOperations<String, String> = mock()

    whenever(template.opsForValue()).thenReturn(valueOps)
    whenever(template.opsForZSet()).thenReturn(zSetOps)

    whenever(template.execute(any<RedisScript<Any>>(), any<List<String>>(), any<List<Any>>()))
        .thenAnswer { invocation ->
            val script = invocation.getArgument<RedisScript<*>>(0)
            val keys = invocation.getArgument<List<String>>(1)
            val args = invocation.getArgument<List<*>>(2).map { it.toString() }
            val result = sandbox.eval(script.scriptAsString, keys, args)
            Flux.fromIterable(listOfNotNull(coerce(result, script.resultType)))
        }

    whenever(valueOps.get(any<String>())).thenAnswer { invocation ->
        Mono.justOrEmpty(sandbox.get(invocation.getArgument<Any>(0).toString()))
    }

    whenever(zSetOps.rangeByScore(any<String>(), any<Range<Double>>(), any<Limit>()))
        .thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val range = invocation.getArgument<Range<Double>>(1)
            val limit = invocation.getArgument<Limit>(2)
            Flux.fromIterable(byScore(sandbox, key, range).let { if (limit.count > 0) it.take(limit.count) else it })
        }

    whenever(zSetOps.rangeByScore(any<String>(), any<Range<Double>>()))
        .thenAnswer { invocation ->
            Flux.fromIterable(byScore(sandbox, invocation.getArgument(0), invocation.getArgument(1)))
        }

    whenever(zSetOps.range(any<String>(), any<Range<Long>>())).thenAnswer { invocation ->
        val key = invocation.getArgument<String>(0)
        val range = invocation.getArgument<Range<Long>>(1)
        val upper = range.upperBound.value.orElse(Long.MAX_VALUE)
        val lower = range.lowerBound.value.orElse(0L)
        val ordered = byScore(sandbox, key, Range.unbounded())
        Flux.fromIterable(ordered.filterIndexed { index, _ -> index.toLong() in lower..upper })
    }

    whenever(zSetOps.add(any<String>(), any<String>(), any<Double>())).thenAnswer { invocation ->
        sandbox.zadd(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
        Mono.just(true)
    }

    whenever(zSetOps.remove(any<String>(), anyVararg())).thenAnswer { invocation ->
        val key = invocation.getArgument<String>(0)
        val members = invocation.arguments.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        val removed = members.count { member ->
            val existed = sandbox.zscore(key, member.toString()) != null
            sandbox.zrem(key, member.toString())
            existed
        }
        Mono.just(removed.toLong())
    }

    return MatchTicketRepository(template, objectMapper, properties)
}

private fun byScore(sandbox: RedisScriptSandbox, key: String, range: Range<Double>): List<String> {
    val upper = range.upperBound.value.orElse(Double.POSITIVE_INFINITY)
    val lower = range.lowerBound.value.orElse(Double.NEGATIVE_INFINITY)
    return sandbox.zmembers(key)
        .mapNotNull { member -> sandbox.zscore(key, member)?.let { member to it } }
        .filter { (_, score) -> score in lower..upper }
        .sortedBy { (_, score) -> score }
        .map { (member, _) -> member }
}

private fun coerce(result: Any?, resultType: Class<*>?): Any? = when {
    result == null -> null
    resultType == Long::class.java || resultType == java.lang.Long::class.java -> (result as Number).toLong()
    else -> result.toString()
}
