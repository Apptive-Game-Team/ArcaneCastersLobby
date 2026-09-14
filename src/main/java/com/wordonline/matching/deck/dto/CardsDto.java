package com.wordonline.matching.deck.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CardsDto {
    private final long id;
    private final String name;
    private final String element;
    public int count;

    public CardsDto(CardDto cardDto, int count) {
        this(cardDto.id(), cardDto.name(), cardDto.element(), count);
    }
}
