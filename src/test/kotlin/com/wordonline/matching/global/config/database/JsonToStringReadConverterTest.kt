package com.wordonline.matching.global.config.database

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.ConnectionFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.core.convert.support.DefaultConversionService

/**
 * A jsonb column read into a `String` entity field used to arrive as the text
 * `JsonByteArrayInput{{"version":1,...}}`, because nothing converted the driver's decoded
 * value and Spring fell back to `Object::toString`. `MagicDto.indicator` re-emits that
 * field with `@JsonRawValue`, so the wrapper text went into `GET /api/data/magics`
 * unquoted and the client could not parse the response at all.
 *
 * The existing DTO and service tests build a `MagicDto` by hand and never touch the
 * database read path, which is why they stayed green through that bug. These two cover
 * the read path: the first that the converter returns the document, the second that
 * `R2dbcConfig` actually registers it.
 */
class JsonToStringReadConverterTest {

    private val indicator =
        """{"version":1,"layers":[{"shape":"circle","radius":{"parameter":"radius"}}]}"""

    /** This is the shape r2dbc-postgresql decodes a jsonb column into. */
    private fun decodedColumn(): Json = Json.of(indicator.toByteArray())

    @Test
    fun `a decoded jsonb column converts to the document text`() {
        assertThat(JsonToStringReadConverter().convert(decodedColumn())).isEqualTo(indicator)
    }

    @Test
    fun `R2dbcConfig registers the converter, so Json never falls back to toString`() {
        val conversionService = DefaultConversionService()
        R2dbcConfig(mock<ConnectionFactory>()).r2dbcCustomConversions()
            .registerConvertersIn(conversionService)

        val converted = conversionService.convert(decodedColumn(), String::class.java)

        assertThat(converted)
            .`as`("without the converter this is JsonByteArrayInput{...}, not the document")
            .isEqualTo(indicator)
    }
}
