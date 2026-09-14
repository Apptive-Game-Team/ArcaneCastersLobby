package com.wordonline.matching.deck.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import lombok.Getter;

@Getter
@Table(name = "magics")
public class Card {
    @Id
    private Long id;
    private String name;
    private String element;
}
