package com.wordonline.matching.deck.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.stream.Collectors;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.auth.repository.UserRepository;
import com.wordonline.matching.deck.domain.Deck;
import com.wordonline.matching.deck.domain.DeckCard;
import com.wordonline.matching.deck.dto.CardDto;
import com.wordonline.matching.deck.dto.CardListItem;
import com.wordonline.matching.deck.dto.CardPoolDto;
import com.wordonline.matching.deck.dto.DeckCardDto;
import com.wordonline.matching.deck.dto.DeckRequestDto;
import com.wordonline.matching.deck.dto.DeckResponseDto;
import com.wordonline.matching.deck.repository.DeckCardRepository;
import com.wordonline.matching.deck.repository.DeckRepository;
import com.wordonline.matching.deck.validation.DeckValidator;
import com.wordonline.matching.global.service.LocalizationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class DeckService {

    private final DeckValidator deckValidator;
    private final UserRepository userRepository;
    private final DeckRepository deckRepository;
    private final DeckDataService deckDataService;
    private final LocalizationService localizationService;
    private final CardListService cardListService;
    private final DeckCardRepository deckCardRepository;

    @Transactional(readOnly = true)
    public Mono<Boolean> hasSelectedDeck(long userId) {
        return userRepository.findById(userId)
                .map(user -> user.getSelectedDeckId() != null);
    }

    @Transactional(readOnly = true)
    public Mono<Boolean> hasValidSelectedDeck(long userId) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    if (user.getSelectedDeckId() == null) {
                        return Mono.just(false);
                    }
                    return deckCardRepository.findAllByDeckId(user.getSelectedDeckId())
                            .flatMap(deckCard -> Flux.range(0, deckCard.getCount()).map(i -> deckCard.getMagicId()))
                            .collectList()
                            .flatMap(cardIds -> deckValidator.isValid(userId, cardIds));
                })
                .defaultIfEmpty(false);
    }

    @Transactional
    public Flux<DeckResponseDto> getDecks(long userId){
        return deckRepository.findAllByUserId(userId)
                .flatMap(this::mapToDeckResponseDto);
    }

    private Mono<DeckResponseDto> mapToDeckResponseDto(Deck deck) {
        return deckCardRepository.findAllByDeckId(deck.getId())
                .flatMap(deckCard -> Flux.range(0, deckCard.getCount()).map(i -> deckCard.getMagicId()))
                .collectList()
                .flatMap(cardIds-> Flux.fromIterable(cardIds).flatMap(deckDataService::getCardDto).collectList())
                .map(cardDtos ->
                        new DeckResponseDto(
                                deck.getId(),
                                deck.getName(),
                                cardDtos));
    }

    public Mono<DeckResponseDto> saveDeck(long userId, DeckRequestDto deckRequestDto) {
        Deck deck = new Deck(userId, deckRequestDto.name());
        return deckValidator.isValid(userId, deckRequestDto.cardIds())
                .flatMap(isValid -> {
                    if (!isValid) {
                        return Mono.error(new IllegalArgumentException("Invalid deck"));
                    }
                    return deckRepository.save(deck)
                            .flatMap(savedDeck -> saveCardsToDeck(savedDeck.getId(), deckRequestDto)
                                    .thenReturn(savedDeck))
                            .flatMap(savedDeck -> getDeck(savedDeck.getId()));
                });
    }

    public Mono<DeckResponseDto> updateDeck(long userId, long deckId, DeckRequestDto deckRequestDto) {
        return deckValidator.isValid(userId, deckRequestDto.cardIds())
                .flatMap(isValid -> {
                    if (!isValid) {
                        return Mono.error(new IllegalArgumentException("Invalid deck"));
                    }
                    return findDeck(deckId, userId)
                            .flatMap(deck ->
                                    // 기존 카드 삭제
                                    deckCardRepository.deleteByDeckId(deckId)
                                            .then(deckRepository.updateDeckName(deckId, deckRequestDto.name()))
                                            .then(saveCardsToDeck(deckId, deckRequestDto))
                                            .then(Mono.just(deckId))
                            )
                            .flatMap(this::getDeck);
                });
    }

    private Mono<Void> saveCardsToDeck(long deckId, DeckRequestDto deckRequestDto) {
        return Flux.fromStream(deckRequestDto.cardIds().stream()
                        .collect(Collectors.groupingBy(
                                Long::longValue,
                                Collectors.counting()
                        )).entrySet().stream())
                .flatMap(entry -> {
                    DeckCard deckCard = new DeckCard(deckId, entry.getKey(), entry.getValue().intValue());
                    return deckCardRepository.save(deckCard);
                })
                .then();
    }

    private Mono<Deck> findDeck(long deckId, long userId) {
        return deckRepository.findById(deckId)
                .switchIfEmpty(throwException(IllegalArgumentException.class, "error.deck.not.found"))
                .filter(deck -> deck.getUserId() == userId)
                .switchIfEmpty(throwException(IllegalArgumentException.class, "error.not.authorized"));
    }

    private <T> Mono<T> throwException(Class<? extends Exception> exceptionClass, String message) {
        return Mono.deferContextual(ctx -> {
            LocaleContext localeContext = ctx.get(LocaleContext.class);
            String defaultName = localizationService.getMessage(localeContext, message);
            try {
                Constructor<?> constructor = exceptionClass.getConstructor(String.class);
                return Mono.error((Exception) constructor.newInstance(defaultName));
            } catch (NoSuchMethodException | InvocationTargetException | InstantiationException |
                     IllegalAccessException e) {
                return Mono.error(new RuntimeException(defaultName));
            }
        });
    }

    public Mono<Void> selectDeck(long userId, long deckId) {
        return findDeck(deckId, userId)
                .flatMap(deck -> userRepository.updateSelectedDeck(userId, deck.getId()))
                .then();
    }

    public Mono<Void> deleteDeck(long userId, long deckId) {
        return findDeck(deckId, userId)
                .flatMap(deck -> userRepository.clearSelectedDeck(userId, deckId)
                        .then(deckCardRepository.deleteByDeckId(deckId))
                        .then(deckRepository.delete(deck)));
    }


    @Transactional(readOnly = true)
    public Mono<DeckResponseDto> getDeck(long deckId) {
        return deckRepository.findById(deckId)
                .flatMapMany(deck ->
                    deckCardRepository.findAllByDeckId(deckId)
                            .flatMap(deckDataService::getCardsDto)
                            .map(cardsDto -> new DeckCardDto(deck, new CardDto(cardsDto), cardsDto.getCount())))
                .collectList()
                .map(DeckResponseDto::new);
    }

    @Transactional(readOnly = true)
    public Mono<CardPoolDto> getCardPool(long userId) {
        return cardListService.getMyCards(userId)
                .map(response -> response.cards().stream()
                        .filter(CardListItem::unlocked)
                        .toList())
                .map(CardPoolDto::new);
    }
}
