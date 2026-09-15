package com.wordonline.matching.magic.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Getter
@Table(name = "magics")
@NoArgsConstructor
@AllArgsConstructor
public class Magic {

    @Id
    private Long id;
    private String name;

    @Column("cast_type")
    private String castType;

    /**
     * jsonb document that draws the magic's aim indicator (see docs/api/data-api.md).
     * The lobby server never parses this value: r2dbc-postgresql decodes the jsonb column
     * into an {@code io.r2dbc.postgresql.codec.Json}, which
     * {@link com.wordonline.matching.global.config.database.JsonToStringReadConverter}
     * turns into the document text, and {@link com.wordonline.matching.magic.dto.MagicDto}
     * re-emits it inline with {@code @JsonRawValue} so it reaches the client unmodified.
     * Drop that converter and this field holds {@code JsonByteArrayInput{...}} instead,
     * which {@code @JsonRawValue} writes into the response unquoted.
     */
    private String indicator;
}
