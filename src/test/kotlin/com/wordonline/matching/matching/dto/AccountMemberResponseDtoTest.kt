package com.wordonline.matching.matching.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

class AccountMemberResponseDtoTest {
    private val mapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()

    @Test
    fun `guest 필드가 없는 계정 서버 응답은 guest 가 false 로 읽힌다`() {
        val dto = mapper.readValue("""{"email":"a@b.c","name":"n","id":3}""", AccountMemberResponseDto::class.java)
        assertThat(dto.guest).isFalse()
    }

    @Test
    fun `guest 필드가 true 이면 그대로 읽힌다`() {
        val dto = mapper.readValue("""{"email":"a@b.c","name":"n","id":3,"guest":true}""", AccountMemberResponseDto::class.java)
        assertThat(dto.guest).isTrue()
    }
}
