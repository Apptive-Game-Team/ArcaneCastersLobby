package com.wordonline.matching.magic.repository;

import com.wordonline.matching.magic.domain.Magic;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface MagicRepository extends R2dbcRepository<Magic, Long> {

    @Query("""
        SELECT m.*
        FROM magics m
        WHERE m.updated_at > :timestamp
    """)
    Flux<Magic> findAllUpdatedSince(LocalDateTime timestamp);
}
