package com.wordonline.matching.global.config.database

import io.r2dbc.postgresql.codec.Json
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter

/**
 * Hands a jsonb column to a `String` entity field as the document text itself.
 *
 * r2dbc-postgresql decodes jsonb into [Json], never into [String]. Without this converter
 * Spring falls back to `Object::toString`, and the decoded value's `toString` returns
 * `JsonByteArrayInput{{"version":1,...}}` - the wrapper class name wrapped around the
 * document. A field that is re-emitted with `@JsonRawValue`, such as `Magic.indicator`,
 * then writes that text into the response body unquoted and the client fails to parse the
 * whole payload.
 */
@ReadingConverter
class JsonToStringReadConverter : Converter<Json, String> {

    override fun convert(source: Json): String = source.asString()
}
