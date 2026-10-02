package com.wordonline.matching.auth.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class User {
    @Id
    private Long id;
    private UserStatus status;
    @Setter
    private Long selectedDeckId;
    private Integer totalWins;
    private Long mmr;
    // How far the player is through the tutorial, from 0.5 to 1.0. Set by the database default on
    // insert and raised by the game server on each win against the tutorial opponent. Read here so
    // the lobby can route their practice match.
    private Float noviceProgress;
    // Player look the client draws, from users.appearance. Null means the client uses its default.
    // The value is passed to the client as stored; unknown values are not rejected here.
    private String appearance;

    public User(long memberId) {
        this(memberId, UserStatus.Online, null, 0, null, null, null);
    }
}
