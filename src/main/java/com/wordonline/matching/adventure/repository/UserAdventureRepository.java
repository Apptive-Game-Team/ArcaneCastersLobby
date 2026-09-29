package com.wordonline.matching.adventure.repository;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Repository
public class UserAdventureRepository {

    private final DatabaseClient databaseClient;

    public Flux<AdventureDatabaseDto> findAllAdventureProgress(Long userId) {
        String sql = """
            SELECT
                a.id AS adventure_id,
                CASE
                    WHEN adv_agg.all_finished THEN 'FINISHED'
                    WHEN adv_agg.any_started THEN 'ACTIVE'
                    ELSE 'INACTIVE'
                END AS adventure_state,
                st.id AS stage_id,
                CASE
                    WHEN stage_agg.all_finished THEN 'FINISHED'
                    WHEN stage_agg.any_started THEN 'ACTIVE'
                    ELSE 'INACTIVE'
                END AS stage_state,
                sc.id AS scenario_id,
                COALESCE(usc.state, 'INACTIVE') AS scenario_state
            FROM adventures a
            JOIN stages st ON a.id = st.adventure_id
            JOIN scenarios sc ON st.id = sc.stage_id
            LEFT JOIN user_scenarios usc ON sc.id = usc.scenario_id AND usc.user_id = :userId
            -- Stage and adventure state come from every scenario under them, one row per scenario.
            -- A scenario the user has no row for counts as INACTIVE, so it keeps its stage unfinished.
            LEFT JOIN LATERAL (
                SELECT
                    bool_and(COALESCE(usc2.state, 'INACTIVE') = 'FINISHED') AS all_finished,
                    bool_or(COALESCE(usc2.state, 'INACTIVE') IN ('ACTIVE', 'FINISHED')) AS any_started
                FROM scenarios sc2
                LEFT JOIN user_scenarios usc2 ON sc2.id = usc2.scenario_id AND usc2.user_id = :userId
                WHERE sc2.stage_id = st.id
            ) stage_agg ON TRUE
            LEFT JOIN LATERAL (
                SELECT
                    bool_and(COALESCE(usc3.state, 'INACTIVE') = 'FINISHED') AS all_finished,
                    bool_or(COALESCE(usc3.state, 'INACTIVE') IN ('ACTIVE', 'FINISHED')) AS any_started
                FROM stages st3
                JOIN scenarios sc3 ON st3.id = sc3.stage_id
                LEFT JOIN user_scenarios usc3 ON sc3.id = usc3.scenario_id AND usc3.user_id = :userId
                WHERE st3.adventure_id = a.id
            ) adv_agg ON TRUE
            ORDER BY a.id, st.id, sc.id
            """;

        return databaseClient.sql(sql)
                .bind("userId", userId)
                .map((row, metadata) -> new AdventureDatabaseDto(
                        row.get("adventure_id", Long.class),
                        row.get("adventure_state", String.class),
                        row.get("stage_id", Long.class),
                        row.get("stage_state", String.class),
                        row.get("scenario_id", Long.class),
                        row.get("scenario_state", String.class)
                ))
                .all()
                .doOnNext(dto -> System.out.println("Mapped DTO: " + dto)); // 여기서 데이터 확인
    }
}
