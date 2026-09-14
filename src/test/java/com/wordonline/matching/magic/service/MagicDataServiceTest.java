package com.wordonline.matching.magic.service;

import com.wordonline.matching.magic.domain.Magic;
import com.wordonline.matching.magic.dto.MagicListRow;
import com.wordonline.matching.magic.dto.MagicsResponse;
import com.wordonline.matching.magic.repository.MagicQueryRepository;
import com.wordonline.matching.magic.repository.MagicRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicDataServiceTest {

    @Mock
    private MagicRepository magicRepository;

    @Mock
    private MagicQueryRepository magicQueryRepository;

    @InjectMocks
    private MagicDataService magicDataService;

    @Test
    @DisplayName("버전_조회시_변경이_있으면_전체_마법_정보를_반환")
    void getMagics_WithVersion_ReturnsAllMagicsWhenAnyChangeExists() {
        String currentVersion = "2024-01-01T00:00:00";
        LocalDateTime changedAt = LocalDateTime.parse("2024-01-02T12:00:00");

        Magic changedMagic = new Magic(20L, "ice_wall", "Water", changedAt);
        Magic unchangedMagic = new Magic(10L, "fireball", "Fire", LocalDateTime.parse("2024-01-01T00:00:00"));

        when(magicRepository.findAllUpdatedSince(any()))
                .thenReturn(Flux.just(changedMagic));
        when(magicRepository.findAll())
                .thenReturn(Flux.just(unchangedMagic, changedMagic));
        when(magicQueryRepository.findAllWithManaCostAndAimShape())
                .thenReturn(Flux.just(
                        new MagicListRow(10L, "fireball", "Fire", 15.0, 1.0),
                        new MagicListRow(20L, "ice_wall", "Water", 20.0, 0.0)
                ));

        StepVerifier.create(magicDataService.getMagics(currentVersion))
                .assertNext(response -> {
                    assertFullMagicSnapshot(response);
                    assert response.version().equals("2024-01-02T12:00:00");
                    assert response.requiresRefresh();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("버전없이_조회시_전체_스냅샷과_changed_true를_반환")
    void getMagics_WithoutVersion_ReturnsFullSnapshotAndChangedTrue() {
        LocalDateTime updatedAt = LocalDateTime.parse("2024-01-02T12:00:00");
        Magic magic = new Magic(10L, "fireball", "Fire", updatedAt);

        when(magicRepository.findAll())
                .thenReturn(Flux.just(magic));
        when(magicQueryRepository.findAllWithManaCostAndAimShape())
                .thenReturn(Flux.just(new MagicListRow(10L, "fireball", "Fire", 15.0, 1.0)));

        StepVerifier.create(magicDataService.getMagics(null))
                .assertNext(response -> {
                    assert response.magics().size() == 1;
                    assert response.version().equals("2024-01-02T12:00:00");
                    assert response.requiresRefresh();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("버전_조회시_변경이_없으면_빈_응답과_기존_버전을_반환")
    void getMagics_WithVersion_ReturnsEmptyWhenNothingChanged() {
        String currentVersion = "2024-01-01T00:00:00";

        when(magicRepository.findAllUpdatedSince(any()))
                .thenReturn(Flux.empty());

        StepVerifier.create(magicDataService.getMagics(currentVersion))
                .assertNext(response -> {
                    assert response.magics().isEmpty();
                    assert response.version().equals(currentVersion);
                    assert !response.requiresRefresh();
                })
                .verifyComplete();
    }

    private void assertFullMagicSnapshot(MagicsResponse response) {
        assert response.magics().size() == 2;
        assert response.magics().stream().anyMatch(magic ->
                magic.id().equals(10L)
                        && magic.name().equals("fireball")
                        && magic.element().equals("Fire")
                        && magic.manaCost().equals(15)
                        && magic.aimShape().equals(1));
        assert response.magics().stream().anyMatch(magic ->
                magic.id().equals(20L)
                        && magic.name().equals("ice_wall")
                        && magic.element().equals("Water")
                        && magic.manaCost().equals(20)
                        && magic.aimShape().equals(0));
    }
}
