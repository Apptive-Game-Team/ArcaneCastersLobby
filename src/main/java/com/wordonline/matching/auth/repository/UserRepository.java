package com.wordonline.matching.auth.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;

import com.wordonline.matching.auth.domain.User;
import com.wordonline.matching.auth.domain.UserStatus;

import reactor.core.publisher.Mono;

public interface UserRepository extends R2dbcRepository<User, Long> {

    @Query("""
UPDATE users
SET selected_deck_id = :deckId
WHERE id = :userId;
""")
    Mono<Long> updateSelectedDeck(@Param("userId") Long userId, @Param("deckId") Long deckId);

    @Query("""
UPDATE users
SET selected_deck_id = NULL
WHERE id = :userId AND selected_deck_id = :deckId;
""")
    Mono<Long> clearSelectedDeck(@Param("userId") Long userId, @Param("deckId") Long deckId);

    @Query("""
UPDATE users
SET status = CAST(:status AS user_status)
WHERE id = :userId;
""")
    Mono<Long> updateStatus(@Param("userId") Long userId, @Param("status") UserStatus status);

    @Query(
            """
            INSERT INTO users(id, status) VALUES
            (:userId, 'Online');
            """
    )
    Mono<Long> insertUser(@Param("userId") Long userId);

    @Query(
            """
            INSERT INTO user_magics(user_id, magic_id, count)
            (
                SELECT :userId, m.id, 3
                FROM magics m
                WHERE m.access_type = 'DEFAULT'
            );
            """
    )
    Mono<Void> initUserMagic(@Param("userId") Long userId);

    @Query(
            """
            INSERT INTO user_quests(user_id, quest_id, state)
            (
                SELECT :userId, q.id, 'IN_PROGRESS'
                FROM quests q
                WHERE q.access_type = 'DEFAULT'
            );
            """
    )
    Mono<Void> initUserQuest(@Param("userId") Long userId);

    @Query(
            """
            WITH inserted_decks AS (
               INSERT INTO decks(name, user_id)
               (
                   SELECT name, :userId
                   FROM decks d
                   WHERE d.user_id = 0
               )
               RETURNING id, name
            ),
            inserted_deck_ids AS (
                INSERT INTO deck_cards(deck_id, magic_id, count)
                (
                    SELECT i.id, dc.magic_id, dc.count
                    FROM inserted_decks i
                    JOIN decks d ON i.name = d.name AND d.user_id = 0
                    JOIN deck_cards dc ON d.id = dc.deck_id
                )
                RETURNING deck_id
            )
            UPDATE users SET selected_deck_id = (SELECT MIN(deck_id) FROM inserted_deck_ids)
            WHERE users.id = :userId
            """
    )
    Mono<Void> initUserDeck(@Param("userId") Long userId);
}
