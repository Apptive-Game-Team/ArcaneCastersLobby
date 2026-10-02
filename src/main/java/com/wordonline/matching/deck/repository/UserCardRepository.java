package com.wordonline.matching.deck.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;

import com.wordonline.matching.deck.domain.UserCard;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserCardRepository extends R2dbcRepository<UserCard, Long> {

    Flux<UserCard> findAllByUserId(Long userId);

    // Adds copies instead of replacing the count: a user who already owns the magic keeps what they had.
    @Modifying
    @Query("""
INSERT INTO user_magics(user_id, magic_id, count)
VALUES (:userId, :magicId, :amount)
ON CONFLICT (user_id, magic_id) DO UPDATE SET count = user_magics.count + EXCLUDED.count
""")
    Mono<Long> addCount(@Param("userId") Long userId, @Param("magicId") Long magicId, @Param("amount") Integer amount);
}
