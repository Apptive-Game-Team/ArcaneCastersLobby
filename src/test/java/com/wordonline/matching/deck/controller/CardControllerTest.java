package com.wordonline.matching.deck.controller;

import com.wordonline.matching.deck.dto.CardListItem;
import com.wordonline.matching.deck.dto.CardListResponse;
import com.wordonline.matching.deck.service.CardListService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import com.wordonline.matching.auth.service.UserIdResolver;

import static org.mockito.Mockito.when;

@WebFluxTest(CardController.class)
@Import(UserIdResolver.class)
class CardControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CardListService cardListService;

    @Test
    @DisplayName("내_카드_목록_조회_엔드포인트가_서비스가_돌려준_카드만_그대로_내려준다")
    void getMyCards_ReturnsExactlyWhatServiceProvides() {
        long userId = 1L;

        // CardListService 가 이미 purpose = 'PLAYER' 로 걸러낸 카드만 돌려준다고 가정하고,
        // 컨트롤러가 그 결과를 더하거나 빼지 않고 그대로 내려주는지만 본다. LEGACY 로 은퇴한
        // 마법(예: water_shot)은 이 목록에 없어야 영구히 잠긴 줄로 남지 않는다.
        CardListResponse mockResponse = new CardListResponse(List.of(
                new CardListItem(1L, "fireball", "Fire", 3, 2, true, null, null, "Shot")
        ));

        when(cardListService.getMyCards(userId)).thenReturn(Mono.just(mockResponse));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("memberId", userId)))
                .get()
                .uri("/api/users/mine/cardLists")
                .exchange()
                .expectStatus().isOk()
                .expectBody(CardListResponse.class)
                .value(response -> {
                    assert response.cards().size() == 1;
                    assert response.cards().get(0).id() == 1L;
                    assert response.cards().get(0).name().equals("fireball");
                });
    }
}
