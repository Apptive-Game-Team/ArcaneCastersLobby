package com.wordonline.matching.matching.repository

import com.wordonline.matching.magic.domain.Magic
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux

/**
 * 랜덤 덱을 뽑을 때 쓰는 후보 조회다.
 *
 * 카드 목록 API 가 쓰는 [com.wordonline.matching.deck.repository.CardListQueryRepository.findMyCardList]
 * 와 따로 두었다. 랜덤 덱은 `cast_kind` 가 필요한데 그쪽 projection 은 client 응답 모양이라 필드를
 * 더할 수 없다.
 */
interface RandomDeckCandidateRepository : R2dbcRepository<Magic, Long> {

    @Query(
        """
select
  m.id as "magic_id",
  um.count as "owned_count",
  m.cast_kind as "cast_kind"
from magics m
join user_magics um on um.magic_id = m.id and um.user_id = :userId
where um.count > 0
order by m.id
""",
    )
    fun findOwnedMagics(userId: Long): Flux<RandomDeckCandidate>
}

/** 한 사용자가 가진 마법 한 종류. `ownedCount` 는 그 마법을 덱에 넣을 수 있는 최대 장수다. */
data class RandomDeckCandidate(
    val magicId: Long,
    val ownedCount: Int,
    val castKind: String,
)
