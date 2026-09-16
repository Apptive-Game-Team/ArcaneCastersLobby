package com.wordonline.matching.deck.dto;

public record MyCardListRow(
        Long id,
        String name,
        String element,
        Integer manaCost,
        int count,
        boolean unlocked,
        String unlockText,
        String progressText
) {}