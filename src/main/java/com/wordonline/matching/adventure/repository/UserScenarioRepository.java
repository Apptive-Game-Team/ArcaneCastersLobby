package com.wordonline.matching.adventure.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.wordonline.matching.adventure.domain.UserScenario;

import reactor.core.publisher.Mono;

public interface UserScenarioRepository extends R2dbcRepository<UserScenario, Long> {

    @Query(
            """
            INSERT INTO user_scenarios(user_id, scenario_id, state)
                (
                    SELECT :userId, MIN(sc.id), 'ACTIVE'
                    FROM scenarios sc
                        JOIN stages s ON sc.stage_id = s.id
                        JOIN adventures a ON a.id = s.adventure_id
                    WHERE a.access_type = 'FREE'
                    GROUP BY s.adventure_id
                )
            ON CONFLICT (user_id, scenario_id)
            DO UPDATE SET state ='ACTIVE' WHERE user_scenarios.state = 'INACTIVE'
            """
    )
    Mono<Void> updateStateActiveFreeAdventure(Long userId);

    @Query(
            """
            WITH scenario_flow AS (
                SELECT
                    s.id AS scenario_id,
                    s.stage_id,
                    st.adventure_id,
                    LAG(us.state) OVER (PARTITION BY st.adventure_id ORDER BY s.id) as prev_state,
                    us.state as current_state
                FROM scenarios s
                JOIN stages st ON s.stage_id = st.id
                LEFT JOIN user_scenarios us ON us.scenario_id = s.id AND us.user_id = :userId
            )
            INSERT INTO user_scenarios (user_id, scenario_id, state)
            SELECT
                :userId,
                sf.scenario_id,
                'ACTIVE'
            FROM scenario_flow sf
            WHERE
                (sf.current_state IS NULL OR sf.current_state = 'INACTIVE')
                AND sf.prev_state = 'FINISHED'
            ON CONFLICT (user_id, scenario_id)
            DO UPDATE SET
                state = 'ACTIVE'
            WHERE user_scenarios.state = 'INACTIVE';
            """
    )
    Mono<Void> updateStateActiveWhenBeforeScenarioIsFinished(Long userId);
}
