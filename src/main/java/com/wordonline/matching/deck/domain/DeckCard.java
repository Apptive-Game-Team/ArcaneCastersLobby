package com.wordonline.matching.deck.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Table("deck_cards")
public class DeckCard {

    @Id
    private Long id;
    private Long deckId;
    private Long magicId;
    private Integer count;

    public DeckCard(Long deckId, Long magicId, Integer count) {
        this(null, deckId, magicId, count);
    }
}
