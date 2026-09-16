package com.wordonline.matching.magic.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

public record MagicDto(
        Long id,
        String name,
        String element,
        Integer manaCost,
        /**
         * jsonb document from {@code magics.indicator}, carried through as-is. The lobby
         * server does not parse or validate it: the query reads the column as
         * {@code m.indicator::text}. {@code @JsonRawValue} tells Jackson to emit the
         * string's content inline as a JSON object instead of an escaped string; a
         * {@code null} value still serializes as a real JSON {@code null}.
         */
        @JsonRawValue String indicator
) {
}
