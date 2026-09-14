package com.wordonline.matching.magic.dto

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * `indicator` carries a jsonb document from `magics.indicator` as a plain `String`
 * (r2dbc-postgresql decodes a jsonb column directly into a `String`-typed field, so
 * `Magic.indicator` needs no cast or converter). The lobby must not parse or validate
 * that document, only pass it through to the client inline as a JSON object, so
 * `MagicDto.indicator` is annotated `@JsonRawValue`. This confirms Jackson emits the
 * string's content verbatim instead of a quoted, escaped string, and that a null
 * indicator still serializes as a JSON `null` rather than the quoted text `"null"`.
 */
class MagicDtoSerializationTest {
    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()

    @Test
    fun `indicator document is emitted inline, not as an escaped string`() {
        val dto = MagicDto(
            34L,
            "leafair",
            "shoot",
            listOf("Nature"),
            """{"version":1,"layers":[{"shape":"circle","origin":"target","radius":{"parameter":"radius"}}]}""",
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(dto))

        assertThat(json.get("indicator").isObject)
            .`as`("indicator must decode back as a JSON object, not a string")
            .isTrue()
        assertThat(json.get("indicator").get("version").asInt()).isEqualTo(1)
        assertThat(json.get("indicator").get("layers").get(0).get("shape").asText()).isEqualTo("circle")
    }

    @Test
    fun `null indicator serializes as JSON null, not the literal text null`() {
        val dto = MagicDto(1L, "fireball", "shoot", listOf("Fire"), null)

        val json = objectMapper.readTree(objectMapper.writeValueAsString(dto))

        assertThat(json.has("indicator")).isTrue()
        assertThat(json.get("indicator").isNull)
            .`as`("a missing document must round-trip as JSON null, never a quoted \"null\" string")
            .isTrue()
    }
}
