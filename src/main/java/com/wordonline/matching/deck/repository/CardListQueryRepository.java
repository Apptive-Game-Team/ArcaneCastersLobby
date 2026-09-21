package com.wordonline.matching.deck.repository;

import com.wordonline.matching.deck.domain.Card;
import com.wordonline.matching.deck.dto.MyCardListRow;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.Repository;
import reactor.core.publisher.Flux;

public interface CardListQueryRepository extends R2dbcRepository<Card, Long> {

    // purpose 가 PLAYER 가 아닌 magics 행은 unlock_condition_type 이 NULL 이라 애초에 풀 방법이
    // 없다. 필터 없이 그대로 내보내면 collection 화면에 count 0, unlock 문구 없이 영원히 잠긴
    // 카드 한 줄이 남는다.
    @Query("""
select
  m.id as "id",
  m.name as "name",
  m.element as "element",
  mana.value::int as "mana_cost",
  coalesce(um.count, 0) as "count",
  (um.magic_id is not null) as "unlocked",
  case
    when um.magic_id is null and m.unlock_condition_type = 'WIN_COUNT'
      then (m.unlock_required_value::text || '승')
    else null
  end as "unlock_text",
  case
    when um.magic_id is null and m.unlock_condition_type = 'WIN_COUNT'
      then (least(u.total_wins, m.unlock_required_value)::text || '/' || m.unlock_required_value::text)
    else null
  end as "progress_text"
from magics m
join users u on u.id = :userId
left join user_magics um
  on um.user_id = :userId and um.magic_id = m.id
left join game_objects go on go.name = m.name
left join parameters mp on mp.name = 'mana_cost'
left join parameter_values mana on mana.game_object_id = go.id and mana.parameter_id = mp.id
where m.purpose = 'PLAYER'
order by m.id
""")
    Flux<MyCardListRow> findMyCardList(long userId);
}