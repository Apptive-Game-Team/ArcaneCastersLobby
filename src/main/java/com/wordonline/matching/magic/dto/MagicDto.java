package com.wordonline.matching.magic.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;

public record MagicDto(
        Long id,
        String name,
        String castType,
        List<String> cards,
        /**
         * jsonb document from {@code magics.indicator}, carried through as-is. The lobby
         * server does not parse or validate it. {@code @JsonRawValue} tells Jackson to
         * emit the string's content inline as a JSON object instead of an escaped string;
         * a {@code null} value still serializes as a real JSON {@code null}.
         */
        @JsonRawValue String indicator
) {
}
