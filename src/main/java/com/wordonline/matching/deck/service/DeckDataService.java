package com.wordonline.matching.deck.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.deck.domain.Card;
import com.wordonline.matching.deck.domain.DeckCard;
import com.wordonline.matching.deck.dto.CardDto;
import com.wordonline.matching.deck.dto.CardsDto;
import com.wordonline.matching.deck.repository.CardRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeckDataService {

    private final CardRepository cardRepository;

    @Transactional(readOnly = true)
    public Mono<List<Card>> getAllCard() {
        return cardRepository.findAll()
                .collectList()
                .cache();
    }

    @Transactional(readOnly = true)
    public Mono<Map<Long, CardDto>> getCardDtoMap() {
        return getAllCard()
                .map(list ->
                    list.stream().collect(Collectors.toMap(
                            Card::getId,
                            CardDto::new))
                ).cache();
    }

    @Transactional(readOnly = true)
    public Mono<CardDto> getCardDto(long cardId) {
        return getCardDtoMap()
                .map(map -> map.get(cardId));
    }

    @Transactional(readOnly = true)
    public Mono<CardsDto> getCardsDto(DeckCard deckCard) {
        return getCardDtoMap()
                .map(map -> map.get(deckCard.getMagicId()))
                .map(cardDto -> new CardsDto(cardDto, deckCard.getCount()));
    }
}
