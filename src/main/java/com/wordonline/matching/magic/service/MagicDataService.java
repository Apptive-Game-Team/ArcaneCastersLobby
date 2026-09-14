package com.wordonline.matching.magic.service;

import com.wordonline.matching.magic.domain.Magic;
import com.wordonline.matching.magic.dto.MagicDto;
import com.wordonline.matching.magic.dto.MagicListRow;
import com.wordonline.matching.magic.dto.MagicsResponse;
import com.wordonline.matching.magic.repository.MagicQueryRepository;
import com.wordonline.matching.magic.repository.MagicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@Transactional
public class MagicDataService {

    private final MagicRepository magicRepository;
    private final MagicQueryRepository magicQueryRepository;

    @Transactional(readOnly = true)
    public Mono<MagicsResponse> getMagics(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) {
            return buildFullSnapshot(null, true);
        }

        LocalDateTime timestamp = LocalDateTime.parse(currentVersion, DateTimeFormatter.ISO_DATE_TIME);
        return magicRepository.findAllUpdatedSince(timestamp)
                .collectList()
                .flatMap(updatedMagics -> {
                    if (updatedMagics.isEmpty()) {
                        return Mono.just(new MagicsResponse(currentVersion, List.of(), false));
                    }
                    return buildFullSnapshot(currentVersion, true);
                });
    }

    private Mono<MagicsResponse> buildFullSnapshot(String fallbackVersion, boolean requiresRefresh) {
        return Mono.zip(
                        magicRepository.findAll().collectList(),
                        magicQueryRepository.findAllWithManaCostAndAimShape().collectList())
                .map(tuple -> {
                    List<Magic> magics = tuple.getT1();
                    if (magics.isEmpty()) {
                        return new MagicsResponse(fallbackVersion, List.of(), requiresRefresh);
                    }

                    Map<Long, MagicListRow> rowsById = tuple.getT2().stream()
                            .collect(Collectors.toMap(MagicListRow::id, Function.identity()));

                    List<MagicDto> magicDtos = magics.stream()
                            .map(magic -> {
                                MagicListRow row = rowsById.get(magic.getId());
                                Integer manaCost = row != null && row.manaCost() != null
                                        ? row.manaCost().intValue() : null;
                                Integer aimShape = row != null && row.aimShape() != null
                                        ? row.aimShape().intValue() : null;
                                return new MagicDto(magic.getId(), magic.getName(), magic.getElement(),
                                        manaCost, aimShape);
                            })
                            .collect(Collectors.toList());

                    LocalDateTime maxUpdatedAt = magics.stream()
                            .map(Magic::getUpdatedAt)
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);

                    String version = (maxUpdatedAt != null)
                            ? maxUpdatedAt.format(DateTimeFormatter.ISO_DATE_TIME)
                            : fallbackVersion;

                    return new MagicsResponse(version, magicDtos, requiresRefresh);
                });
    }
}
