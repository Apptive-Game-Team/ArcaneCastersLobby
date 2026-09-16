package com.wordonline.matching.deck.validation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.wordonline.matching.deck.domain.UserCard;
import com.wordonline.matching.deck.dto.CardDto;
import com.wordonline.matching.deck.repository.UserCardRepository;
import com.wordonline.matching.deck.service.DeckDataService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * 덱이 저장될 수 있는지 본다.
 *
 * <p>덱 한 벌은 {@value #DECK_CARD_COUNT} 장이다. 그 밖의 구성에는 제한이 없다. 같은 마법을 몇 장
 * 넣었는지도, 원소가 몇 종류인지도 보지 않는다. 나머지 두 가지는 자기 것이 아닌 카드를 덱에 넣지
 * 못하게 하는 검사다: 있는 마법이어야 하고, 가진 장수를 넘지 않아야 한다.
 */
@Service
@RequiredArgsConstructor
public class DeckValidator {

    private final DeckDataService deckDataService;
    private final UserCardRepository userCardRepository;

    public static final int DECK_CARD_COUNT = 15;

    public Mono<Boolean> isValid(long userId, List<Long> cardIds) {
        if (cardIds == null || cardIds.size() != DECK_CARD_COUNT) {
            return Mono.just(false);
        }

        Map<Long, Long> cardCounts = cardIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

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

                    return cardCounts.entrySet().stream()
                            .allMatch(entry -> entry.getValue() <= ownedCounts.getOrDefault(entry.getKey(), 0));
                });
    }
}
