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

    @Query("""
        SELECT max(m.updated_at)
        FROM magics m
    """)
    Mono<LocalDateTime> findMaxUpdatedAt();
}
