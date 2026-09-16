package com.wordonline.matching.deck.dto;

import java.util.List;
import java.util.stream.Stream;

public record DeckResponseDto(
        long id,
        String name,
        List<CardDto> cards
) {

    public DeckResponseDto(List<DeckCardDto> deckCardDtos) {
        this(
                deckCardDtos.getFirst().deckId(),
                deckCardDtos.getFirst().deckName(),
                deckCardDtos.stream()
                        .flatMap(deckCardDto -> Stream.generate(() -> new CardDto(deckCardDto.magicId(), deckCardDto.magicName(), deckCardDto.element())) // 1. 생성
                                .limit(deckCardDto.count()))
                        .toList()
        );
    }
}
