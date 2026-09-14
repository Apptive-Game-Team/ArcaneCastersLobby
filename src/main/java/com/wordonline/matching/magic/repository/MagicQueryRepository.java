package com.wordonline.matching.magic.repository;

import com.wordonline.matching.magic.domain.Magic;
import com.wordonline.matching.magic.dto.MagicListRow;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

public interface MagicQueryRepository extends R2dbcRepository<Magic, Long> {

    @Query("""
select
  m.id as "id",
  m.name as "name",
  m.element as "element",
  mana.value as "mana_cost",
  aim.value as "aim_shape"
from magics m
left join game_objects go on go.name = m.name
left join parameters mp on mp.name = 'mana_cost'
left join parameter_values mana on mana.game_object_id = go.id and mana.parameter_id = mp.id
left join parameters ap on ap.name = 'aim_shape'
left join parameter_values aim on aim.game_object_id = go.id and aim.parameter_id = ap.id
order by m.id
""")
    Flux<MagicListRow> findAllWithManaCostAndAimShape();
}
