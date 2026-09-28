package com.wordonline.matching.session.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.dto.SessionEndedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Pins the wire format of `POST /api/server/game-sessions` against fixtures shared with
 * WordOnlineServer, which keeps byte-identical copies under the same paths.
 *
 * Both repositories previously checked this contract only against their own DTO classes,
 * so a renamed field left both suites green. It would not have surfaced as an exception
 * either: the lobby treats a response it cannot match to its `attemptId` as a refusal and
 * moves to the next candidate, so a broken contract reads as "every server refused" and
 * ends in a 503 with nothing in the logs naming the cause.
 */
class GameServerContractTest {

    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/contract/$name")) { "missing fixture: $name" }
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `the request this server sends matches the shared fixture`() {
        val request = CreateSessionRequest("attempt-1", SessionDto.PVP("session-1", 1L, 2L))

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)))
            .isEqualTo(objectMapper.readTree(fixture("create-session-request.json")))
    }

    @Test
    fun `the response the game server sends is readable`() {
        val response = objectMapper.readValue(
            fixture("session-ready-response.json"),
            SessionReadyResponse::class.java,
        )

        assertThat(response.attemptId).isEqualTo("attempt-1")
        assertThat(response.sessionId).isEqualTo("session-1")
        assertThat(response.ready).isTrue()
        assertThat(response.serverUrl).isEqualTo("http://localhost:7777")
        assertThat(response.webSocketUrl).isEqualTo("http://localhost:7777/ws")
    }

    @Test
    fun `the request stays flat rather than nesting the session`() {
        // The game server binds uid1/uid2/sessionType at the top level, so a wrapper object
        // would leave every field null and every candidate refusing the session.
        val json = objectMapper.readTree(fixture("create-session-request.json"))

        assertThat(json.has("session")).isFalse()
        assertThat(json.has("sessionDto")).isFalse()
        assertThat(json.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder(
                "attemptId",
                "sessionId",
                "uid1",
                "uid2",
                "sessionType",
                "scenarioId",
                "leftDeckCardIds",
                "rightDeckCardIds",
            )
    }

    @Test
    fun `the end-of-session notification the game server sends is readable`() {
        val request = objectMapper.readValue(
            fixture("session-ended-notification.json"),
            SessionEndedRequest::class.java,
        )

        assertThat(request.instanceId).isEqualTo("00000000-0000-0000-0000-000000000001")
    }

    @Test
    fun `the end-of-session notification carries nothing but the instance id`() {
        // The session id travels in the path. A body that also named the users or the ticket
        // would let the caller decide what to close, which is exactly what the instance id
        // check exists to prevent.
        val json = objectMapper.readTree(fixture("session-ended-notification.json"))

        assertThat(json.fieldNames().asSequence().toList()).containsExactly("instanceId")
    }

    @Test
    fun `an unknown field from a newer game server does not break the response`() {
        // The game server may ship a field before the lobby knows about it; rejecting the
        // whole response there would read as a refusal and fail matching for no reason.
        val extended = objectMapper.readTree(fixture("session-ready-response.json")) as com.fasterxml.jackson.databind.node.ObjectNode
        extended.put("regionCode", "kr")

        val response = objectMapper.readValue(extended.toString(), SessionReadyResponse::class.java)

        assertThat(response.sessionId).isEqualTo("session-1")
    }
}
