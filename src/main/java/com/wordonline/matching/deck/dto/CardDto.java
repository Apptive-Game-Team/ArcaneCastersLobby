package com.wordonline.matching.deck.dto;

import com.wordonline.matching.deck.domain.Card;

public record CardDto(
        long id,
        String name,
        String element
) {
    public CardDto(Card card) {
        this(card.getId(), card.getName(), card.getElement());
    }

    public CardDto(CardsDto cardsDto) {
        this(cardsDto.getId(), cardsDto.getName(), cardsDto.getElement());
    }
}

