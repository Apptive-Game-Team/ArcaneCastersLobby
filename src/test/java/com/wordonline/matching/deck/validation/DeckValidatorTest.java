package com.wordonline.matching.deck.validation;

import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wordonline.matching.deck.domain.UserCard;
import com.wordonline.matching.deck.dto.CardDto;
import com.wordonline.matching.deck.repository.UserCardRepository;
import com.wordonline.matching.deck.service.DeckDataService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class DeckValidatorTest {

    private static final long USER_ID = 1L;

    @Mock
    private DeckDataService deckDataService;

    @Mock
    private UserCardRepository userCardRepository;

    @InjectMocks
    private DeckValidator deckValidator;

    @Test
    void isValid_ReturnsTrue_WhenDeckSatisfiesStandard() {
        stubCards();
        stubOwnedCards(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);

        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(
                        1L, 1L, 2L, 3L, 3L, 3L, 4L, 5L, 5L,
                        6L, 7L, 8L, 9L, 10L, 11L
                )))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void isValid_ReturnsFalse_WhenDeckSizeIsNotFifteen() {
        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(1L, 2L)))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isValid_ReturnsFalse_WhenSameCardIsMoreThanThree() {
        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(
                        1L, 1L, 1L, 1L, 2L, 3L, 4L, 5L, 5L,
                        6L, 7L, 8L, 9L, 10L, 11L
                )))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isValid_ReturnsFalse_WhenElementsAreLessThanTwo() {
        stubCards();
        stubOwnedCards(1L, 2L, 3L, 4L, 5L);

        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(
                        1L, 1L, 1L, 2L, 2L, 2L, 3L, 3L, 3L,
                        4L, 4L, 4L, 5L, 5L, 5L
                )))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isValid_ReturnsFalse_WhenCardIsNotOwned() {
        stubCards();
        stubOwnedCards(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(
                        1L, 1L, 2L, 3L, 3L, 3L, 4L, 5L, 5L,
                        6L, 7L, 8L, 9L, 10L, 11L
                )))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void isValid_ReturnsFalse_WhenOwnedCountIsLessThanRequested() {
        stubCards();
        when(userCardRepository.findAllByUserId(USER_ID)).thenReturn(Flux.just(
                new UserCard(USER_ID, 1L, 3),
                new UserCard(USER_ID, 2L, 3),
                new UserCard(USER_ID, 3L, 1),
                new UserCard(USER_ID, 4L, 3),
                new UserCard(USER_ID, 5L, 3),
                new UserCard(USER_ID, 6L, 3),
                new UserCard(USER_ID, 7L, 3),
                new UserCard(USER_ID, 8L, 3),
                new UserCard(USER_ID, 9L, 3),
                new UserCard(USER_ID, 10L, 3),
                new UserCard(USER_ID, 11L, 3)
        ));

        StepVerifier.create(deckValidator.isValid(USER_ID, List.of(
                        1L, 1L, 2L, 3L, 3L, 3L, 4L, 5L, 5L,
                        6L, 7L, 8L, 9L, 10L, 11L
                )))
                .expectNext(false)
                .verifyComplete();
    }

    private void stubCards() {
        when(deckDataService.getCardDtoMap()).thenReturn(Mono.just(cardMap()));
    }

    private void stubOwnedCards(Long... magicIds) {
        when(userCardRepository.findAllByUserId(USER_ID)).thenReturn(
                Flux.fromArray(magicIds).map(magicId -> new UserCard(USER_ID, magicId, 3)));
    }

    private Map<Long, CardDto> cardMap() {
        return Map.ofEntries(
                Map.entry(1L, new CardDto(1L, "fireball", "Fire")),
                Map.entry(2L, new CardDto(2L, "fire_shot", "Fire")),
                Map.entry(3L, new CardDto(3L, "fire_lord_spirit", "Fire")),
                Map.entry(4L, new CardDto(4L, "magma_spirit", "Fire")),
                Map.entry(5L, new CardDto(5L, "fire_explosion", "Fire")),
                Map.entry(6L, new CardDto(6L, "water_shot", "Water")),
                Map.entry(7L, new CardDto(7L, "tide_call", "Water")),
                Map.entry(8L, new CardDto(8L, "bubble_spirit", "Water")),
                Map.entry(9L, new CardDto(9L, "chain_lightning", "Lightning")),
                Map.entry(10L, new CardDto(10L, "rock_golem", "Rock")),
                Map.entry(11L, new CardDto(11L, "leafair", "Nature"))
        );
    }
}
