package com.wordonline.matching.magic.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

public record MagicDto(
        Long id,
        String name,
        String element,
        Integer manaCost,
        @JsonRawValue String indicator
) {
}
