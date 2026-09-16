package com.wordonline.matching.magic.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Getter
@Table(name = "magics")
@NoArgsConstructor
@AllArgsConstructor
public class Magic {

    @Id
    private Long id;
    private String name;
    private String element;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
