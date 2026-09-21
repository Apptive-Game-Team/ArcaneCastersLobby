package com.wordonline.matching.magic.repository;

import com.wordonline.matching.magic.domain.Magic;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface MagicRepository extends R2dbcRepository<Magic, Long> {

    @Query("""
        SELECT m.*
        FROM magics m
        WHERE m.purpose = 'PLAYER'
    """)
    Flux<Magic> findAllPlayerMagics();

    // Change detection and the version read the whole table, including the rows the payload
    // leaves out. A magic whose purpose stops being PLAYER drops out of the payload without
    // changing anything inside it, so a version taken from the payload alone would leave every
    // cached client on the old list. The two also have to count the same rows: a version taken
    // from the payload while detection reads the whole table reports that same change on every
    // request, and the client refetches forever.
    @Query("""
        SELECT m.*
        FROM magics m
        WHERE m.updated_at > :timestamp
    """)
    Flux<Magic> findAllUpdatedSince(LocalDateTime timestamp);

    // R2DBC 는 @Query 결과를 entity 로 읽으므로 max(updated_at) 같은 값 하나짜리 select 가
    // "didn't find a PersistentEntity for java.time.LocalDateTime" 으로 깨진다. 가장 최근 행을
    // 그대로 읽고 서비스가 그 컬럼을 꺼낸다.
    @Query("""
        SELECT m.*
        FROM magics m
        ORDER BY m.updated_at DESC NULLS LAST
        LIMIT 1
    """)
    Mono<Magic> findLatestUpdated();
}
