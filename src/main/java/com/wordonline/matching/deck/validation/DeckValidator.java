package com.wordonline.matching.deck.validation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.wordonline.matching.deck.domain.UserCard;
import com.wordonline.matching.deck.dto.CardDto;
import com.wordonline.matching.deck.repository.UserCardRepository;
import com.wordonline.matching.deck.service.DeckDataService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class DeckValidator {

    private final DeckDataService deckDataService;
    private final UserCardRepository userCardRepository;
    public static final int DECK_CARD_COUNT = 15;
    public static final int LEAST_NUM_OF_ELEMENTS = 2;
    public static final int MAX_NUM_OF_SAME_CARD = 3;

    public Mono<Boolean> isValid(long userId, List<Long> cardIds) {
        if (cardIds == null || cardIds.size() != DECK_CARD_COUNT) {
            return Mono.just(false);
        }

        Map<Long, Long> cardCounts = cardIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        if (cardCounts.values().stream().anyMatch(count -> count > MAX_NUM_OF_SAME_CARD)) {
            return Mono.just(false);
        }

        return Mono.zip(
                        deckDataService.getCardDtoMap(),
                        userCardRepository.findAllByUserId(userId)
                                .collectMap(UserCard::getMagicId, UserCard::getCount))
                .map(tuple -> {
                    Map<Long, CardDto> cards = tuple.getT1();
                    Map<Long, Integer> ownedCounts = tuple.getT2();

                    if (!cards.keySet().containsAll(cardCounts.keySet())) {
                        return false;
                    }

                    boolean allOwned = cardCounts.entrySet().stream()
                            .allMatch(entry -> entry.getValue() <= ownedCounts.getOrDefault(entry.getKey(), 0));
                    if (!allOwned) {
                        return false;
                    }

                    Set<String> elements = cardCounts.keySet().stream()
                            .map(cards::get)
                            .map(CardDto::element)
                            .collect(Collectors.toSet());

                    return elements.size() >= LEAST_NUM_OF_ELEMENTS;
                });
    }
}
