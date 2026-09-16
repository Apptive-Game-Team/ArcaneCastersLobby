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
 * <p>덱 구성에는 제한이 없다. 장수도, 같은 마법을 몇 장 넣었는지도, 원소가 몇 종류인지도 보지 않는다.
 * 남은 것은 자기 것이 아닌 카드를 덱에 넣지 못하게 하는 두 가지뿐이다: 있는 마법이어야 하고, 가진
 * 장수를 넘지 않아야 한다.
 */
@Service
@RequiredArgsConstructor
public class DeckValidator {

    private final DeckDataService deckDataService;
    private final UserCardRepository userCardRepository;

    public Mono<Boolean> isValid(long userId, List<Long> cardIds) {
        // 목록이 아예 없는 것은 빈 덱이 아니라 잘못 만들어진 요청이다.
        if (cardIds == null) {
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
