package com.wordonline.matching.deck.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Table("user_magics")
public class UserCard {

    @Id
    private Long id;
    private Long userId;
    private Long magicId;
    private Integer count;

    public UserCard(Long userId, Long magicId, Integer count) {
        this(null, userId, magicId, count);
    }
}
