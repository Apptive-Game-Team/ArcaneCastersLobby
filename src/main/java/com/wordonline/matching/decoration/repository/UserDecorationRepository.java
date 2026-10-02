package com.wordonline.matching.decoration.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;

import com.wordonline.matching.decoration.entity.DecoType;
import com.wordonline.matching.decoration.entity.UserDecoration;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Deprecated
public interface UserDecorationRepository extends R2dbcRepository<UserDecoration, Long> {

    Mono<Boolean> existsByUserId(Long userId);

    Flux<UserDecoration> findAllByUserId(long memberId);
    Flux<UserDecoration> findAllByUserIdAndIsEquipped(long memberId, boolean isEquipped);

    Mono<UserDecoration> findByUserIdAndDecorationId(long memberId, long decorationId);

    @Modifying // 상태를 변경하는 쿼리이므로 필수!
    @Query("""
    UPDATE user_decorations
    SET is_equipped = FALSE
    WHERE user_id = :memberId
      AND decoration_id IN (
          SELECT id FROM decorations WHERE deco_type = :decoType
      )
    """)
    Mono<Integer> resetIsEquippedByMemberIdAndDecoType(@Param("memberId") long memberId, @Param("decoType") DecoType decoType);

    @Query("""
UPDATE user_decorations
SET is_equipped = TRUE
WHERE user_id = :memberId AND decoration_id = :decorationId
""")
    Mono<Void> setIsEquippedByMemberIdAndDecorationId(
            @Param("memberId") long memberId,
            @Param("decorationId") long decorationId
    );

    // user_decorations has no unique constraint, so the NOT EXISTS keeps a second grant from adding a duplicate row.
    @Modifying
    @Query("""
INSERT INTO user_decorations(user_id, decoration_id, is_equipped)
SELECT :memberId, :decorationId, FALSE
WHERE NOT EXISTS (
    SELECT 1 FROM user_decorations WHERE user_id = :memberId AND decoration_id = :decorationId
)
""")
    Mono<Long> insertIfAbsent(@Param("memberId") long memberId, @Param("decorationId") long decorationId);
}
