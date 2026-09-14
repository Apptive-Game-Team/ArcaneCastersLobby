package com.wordonline.matching.data.controller;

import com.wordonline.matching.data.service.DataService;
import com.wordonline.matching.data.dto.ParametersResponse;
import com.wordonline.matching.magic.dto.MagicDto;
import com.wordonline.matching.magic.dto.MagicsResponse;
import com.wordonline.matching.magic.service.MagicDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(DataController.class)
class DataControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private DataService dataService;

    @MockitoBean
    private MagicDataService magicDataService;

    @Test
    @DisplayName("마법_전체_조회_버전없음_성공")
    void getMagics_WithoutVersion_ReturnsAllMagics() {
        // indicator is a pass-through jsonb document (see MagicDto), so it must not be
        // asserted through a typed getter: it never round-trips back into a String field.
        // The response body is checked as raw JSON instead, confirming the document comes
        // back as an inline JSON object rather than an escaped string.
        String indicatorJson = "{\"version\":1,\"layers\":[{\"shape\":\"circle\",\"radius\":{\"parameter\":\"radius\"}}]}";
        MagicDto magicDto = new MagicDto(1L, "fireball", "shoot", List.of("Fire", "Fire", "Shoot"), indicatorJson);
        MagicsResponse mockResponse = new MagicsResponse("2024-01-01T00:00:00", List.of(magicDto), true);

        when(magicDataService.getMagics(isNull())).thenReturn(Mono.just(mockResponse));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("memberId", "1")))
                .get()
                .uri("/api/data/magics")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo("2024-01-01T00:00:00")
                .jsonPath("$.magics.length()").isEqualTo(1)
                .jsonPath("$.magics[0].id").isEqualTo(1)
                .jsonPath("$.magics[0].name").isEqualTo("fireball")
                .jsonPath("$.magics[0].castType").isEqualTo("shoot")
                .jsonPath("$.magics[0].cards[0]").isEqualTo("Fire")
                .jsonPath("$.magics[0].cards[2]").isEqualTo("Shoot")
                .jsonPath("$.magics[0].indicator.version").isEqualTo(1)
                .jsonPath("$.magics[0].indicator.layers[0].shape").isEqualTo("circle")
                .jsonPath("$.magics[0].indicator.layers[0].radius.parameter").isEqualTo("radius")
                .jsonPath("$.requiresRefresh").isEqualTo(true);
    }

    @Test
    @DisplayName("마법_조회_indicator_없음_null로_반환")
    void getMagics_WithoutIndicator_ReturnsJsonNull() {
        MagicDto magicDto = new MagicDto(2L, "ice_wall", "build", List.of("Water"), null);
        MagicsResponse mockResponse = new MagicsResponse("2024-01-01T00:00:00", List.of(magicDto), true);

        when(magicDataService.getMagics(isNull())).thenReturn(Mono.just(mockResponse));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("memberId", "1")))
                .get()
                .uri("/api/data/magics")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.magics[0].indicator").isEqualTo(null);
    }

    @Test
    @DisplayName("마법_변경사항_조회_버전있음_성공")
    void getMagics_WithVersion_ReturnsUpdatedMagics() {
        String currentVersion = "2024-01-01T00:00:00";
        MagicsResponse mockResponse = new MagicsResponse(currentVersion, List.of(), false);

        when(magicDataService.getMagics(eq(currentVersion))).thenReturn(Mono.just(mockResponse));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("memberId", "1")))
                .get()
                .uri("/api/data/magics?currentVersion=" + currentVersion)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MagicsResponse.class)
                .value(response -> {
                    assert response.version().equals(currentVersion);
                    assert response.magics().isEmpty();
                    assert !response.requiresRefresh();
                });
    }

}
