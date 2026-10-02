package com.wordonline.matching.auth.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wordonline.matching.auth.domain.User;
import com.wordonline.matching.auth.domain.UserStatus;
import com.wordonline.matching.auth.dto.UserResponseDto;
import com.wordonline.matching.auth.repository.UserRepository;
import com.wordonline.matching.global.service.LocalizationService;
import com.wordonline.matching.matching.client.AccountClient;
import com.wordonline.matching.matching.dto.AccountMemberResponseDto;
import com.wordonline.matching.matching.repository.MatchingQueueRepository;
import com.wordonline.matching.server.service.GameSessionService;
import com.wordonline.matching.session.service.SessionRecoveryStore;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class UserServiceAppearanceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccountClient accountClient;
    @Mock
    private LocalizationService localizationService;
    @Mock
    private MatchingQueueRepository matchingQueueRepository;
    @Mock
    private SessionRecoveryStore sessionRecoveryStore;
    @Mock
    private GameSessionService gameSessionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, accountClient, localizationService,
                matchingQueueRepository, sessionRecoveryStore, gameSessionService, null);
    }

    private User userWithAppearance(long id, String appearance) {
        return new User(id, UserStatus.Online, null, 0, null, null, appearance);
    }

    @Test
    void botDetailCarriesAppearanceFromItsUsersRow() {
        when(accountClient.getMember(-7L))
                .thenReturn(Mono.just(new AccountMemberResponseDto("bot@theevilent.com", "storm bot")));
        when(userRepository.findById(-7L)).thenReturn(Mono.just(userWithAppearance(-7L, "storm")));

        StepVerifier.create(userService.getUserDetail(-7L))
                .expectNextMatches(detail -> "storm".equals(detail.appearance()) && detail.id() == -7L)
                .verifyComplete();
    }

    @Test
    void humanDetailSendsNullWhenColumnIsNull() {
        when(accountClient.getMember(1L))
                .thenReturn(Mono.just(new AccountMemberResponseDto("human@example.com", "human")));
        when(userRepository.findById(1L)).thenReturn(Mono.just(userWithAppearance(1L, null)));

        StepVerifier.create(userService.getUserDetail(1L))
                .expectNextMatches(detail -> detail.appearance() == null && detail.name().equals("human"))
                .verifyComplete();
    }

    @Test
    void detailSendsNullWhenUsersRowIsMissing() {
        when(accountClient.getMember(anyLong()))
                .thenReturn(Mono.just(new AccountMemberResponseDto("human@example.com", "human")));
        when(userRepository.findById(2L)).thenReturn(Mono.empty());

        StepVerifier.create(userService.getUserDetail(2L))
                .expectNextMatches(detail -> detail.appearance() == null)
                .verifyComplete();
    }

    @Test
    void unknownAppearanceValueIsPassedThrough() {
        when(accountClient.getMember(3L))
                .thenReturn(Mono.just(new AccountMemberResponseDto("human@example.com", "human")));
        when(userRepository.findById(3L)).thenReturn(Mono.just(userWithAppearance(3L, "future-look")));

        StepVerifier.create(userService.getUserDetail(3L))
                .expectNextMatches(detail -> "future-look".equals(detail.appearance()))
                .verifyComplete();
    }

    @Test
    void ownProfileDtoCarriesAppearance() {
        UserResponseDto dto = new UserResponseDto(userWithAppearance(5L, "golem"));

        org.junit.jupiter.api.Assertions.assertEquals("golem", dto.appearance());
    }

    @Test
    void ownProfileDtoCarriesMmr() {
        User user = new User(5L, UserStatus.Online, null, 0, 1234L, null, null);

        org.junit.jupiter.api.Assertions.assertEquals(1234, new UserResponseDto(user).mmr());
    }

    @Test
    void ownProfileDtoSendsZeroMmrWhenColumnIsNull() {
        org.junit.jupiter.api.Assertions.assertEquals(0, new UserResponseDto(userWithAppearance(5L, null)).mmr());
    }

    @Test
    void ownProfileDtoKeepsNullAppearance() {
        UserResponseDto dto = new UserResponseDto(userWithAppearance(5L, null));

        org.junit.jupiter.api.Assertions.assertNull(dto.appearance());
    }
}
