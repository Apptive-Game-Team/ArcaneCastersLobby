package com.wordonline.matching.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.wordonline.matching.auth.dto.UserGameRecordResponseDto;
import com.wordonline.matching.auth.dto.UserStatisticsGamesResponseDto;
import com.wordonline.matching.auth.service.UserIdResolver;
import com.wordonline.matching.auth.service.UserService;
import com.wordonline.matching.auth.service.UserStatisticsService;
import com.wordonline.matching.global.config.PageableWebFluxConfig;
import com.wordonline.matching.quest.service.QuestService;
import com.wordonline.matching.session.service.LegacyGameMatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@WebFluxTest(UserController.class)
@Import({PageableWebFluxConfig.class, UserIdResolver.class})
class UserControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserStatisticsService userStatisticsService;

    @MockitoBean
    private LegacyGameMatchService gameMatchService;

    @MockitoBean
    private QuestService questService;

    @Test
    @DisplayName("전적_게임_목록_조회시_Pageable_쿼리_파라미터를_해석한다")
    void getMyStatisticsGames_ResolvesPageable() {
        long userId = 1L;
        UserStatisticsGamesResponseDto response = new UserStatisticsGamesResponseDto(List.of(
                new UserGameRecordResponseDto(2L, "win")
        ));

        when(userStatisticsService.getGames(eq(userId), any(Pageable.class))).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("memberId", userId)))
                .get()
                .uri("/api/users/mine/statistics/games?page=2&size=15")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.games[0].opponentId").isEqualTo(2)
                .jsonPath("$.games[0].result").isEqualTo("win");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userStatisticsService).getGames(eq(userId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(15);
    }
}
