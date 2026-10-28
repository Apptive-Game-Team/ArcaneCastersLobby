package com.wordonline.matching.session.dto

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.session.domain.SessionRecoveryInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

class LockstepRecoveryContractTest {
    private val mapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()

    @Test
    fun `재접속 정보는 lockstep 버전과 연결 주소를 보존한다`() {
        val left = UserDetailResponseDto(11L, "left", "left@example.com")
        val right = UserDetailResponseDto(22L, "right", "right@example.com")
        val matched = MatchedInfoDto("matched", "http://localhost:7777", left, right, "session-1",
            "http://localhost:7777/ws", lockstepSimulationVersion = "lockstep-v1", mapType = "RIVER")
        val stored = SessionRecoveryInfo(matched)
        val decoded = mapper.readValue(mapper.writeValueAsString(stored), SessionRecoveryInfo::class.java)
        val recovered = MatchedInfoDto(decoded, left, right)
        assertThat(recovered.lockstepSimulationVersion).isEqualTo(matched.lockstepSimulationVersion)
        assertThat(recovered.webSocketUrl).isEqualTo(matched.webSocketUrl)
        assertThat(recovered.sessionId).isEqualTo(matched.sessionId)
        assertThat(recovered.mapType).isEqualTo("RIVER")
        assertThat(decoded.expireAt()).isEqualTo(stored.expireAt())
    }

    @Test
    fun `기존 복구 JSON에는 lockstep 버전이 없다`() {
        val decoded = mapper.readValue("""{"leftUserId":11,"rightUserId":22,"sessionId":"old","serverUrl":"http://localhost:7777","expireAt":99}""",
            SessionRecoveryInfo::class.java)
        assertThat(decoded.lockstepSimulationVersion()).isNull()
        assertThat(decoded.webSocketUrl()).isNull()
        assertThat(decoded.mapType()).isNull()
        val matched = mapper.readValue("""{"message":"matched","server":"http://localhost:7777","leftUser":{"id":11,"name":"left","email":"left@example.com"},"rightUser":{"id":22,"name":"right","email":"right@example.com"},"sessionId":"old"}""", MatchedInfoDto::class.java)
        assertThat(matched.lockstepSimulationVersion).isNull()
        assertThat(matched.mapType).isNull()
    }
}
