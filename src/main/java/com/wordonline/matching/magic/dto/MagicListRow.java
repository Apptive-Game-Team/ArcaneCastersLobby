package com.wordonline.matching.magic.dto;

public record MagicListRow(
        Long id,
        String name,
        Double manaCost,
        String indicator,
        /**
         * Comma-separated {@code prefab_elements.element} values for {@code magics.prefab},
         * ordered by element name ({@code string_agg(pe.element, ',' order by pe.element)}).
         * {@code null} when the magic has no prefab or the prefab has no rows.
         */
        String elements
) {
}
