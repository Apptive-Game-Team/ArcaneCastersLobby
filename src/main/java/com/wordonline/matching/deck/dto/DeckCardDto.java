package com.wordonline.matching.deck.dto;

import com.wordonline.matching.deck.domain.Deck;

public record DeckCardDto(
        long deckId,
        long magicId,
        int count,
        String deckName,
        String magicName,
        String element
) {
    public DeckCardDto(Deck deck, CardDto cardDto, int count) {
        this(deck.getId(), cardDto.id(), count, deck.getName(), cardDto.name(), cardDto.element());
    }
}
