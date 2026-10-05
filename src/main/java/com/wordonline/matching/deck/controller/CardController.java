package com.wordonline.matching.deck.controller;

import com.wordonline.matching.auth.service.UserId;
import com.wordonline.matching.deck.dto.CardListResponse;
import com.wordonline.matching.deck.service.CardListService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class CardController {

    private final CardListService cardListService;

    public CardController(CardListService cardListService) {
        this.cardListService = cardListService;
    }

    @GetMapping("/api/users/mine/cardLists")
    public Mono<CardListResponse> getMyCards(@UserId Long userId) {
        return cardListService.getMyCards(userId);
    }
}
