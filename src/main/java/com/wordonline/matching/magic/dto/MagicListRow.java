package com.wordonline.matching.magic.dto;

public record MagicListRow(
        Long id,
        String name,
        String element,
        Double manaCost,
        Double aimShape
) {
}
