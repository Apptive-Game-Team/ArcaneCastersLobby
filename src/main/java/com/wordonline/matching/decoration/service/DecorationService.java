package com.wordonline.matching.decoration.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.decoration.dto.DecorationRequest;
import com.wordonline.matching.decoration.dto.DecorationResponse;
import com.wordonline.matching.decoration.entity.DecoType;
import com.wordonline.matching.decoration.entity.Decoration;
import com.wordonline.matching.decoration.entity.UserDecoration;
import com.wordonline.matching.decoration.repository.DecorationRepository;
import com.wordonline.matching.decoration.repository.UserDecorationRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Transactional
@RequiredArgsConstructor
@Deprecated
public class DecorationService {

    private final DecorationRepository decorationRepository;
    private final UserDecorationRepository userDecorationRepository;

    public Flux<DecorationResponse> getDecorationsByUserId(long memberId, boolean equippedOnly) {
        return findDecorationsByUserId(memberId, equippedOnly);
    }

    private Flux<DecorationResponse> findDecorationsByUserId(long memberId, boolean equippedOnly) {
        if (equippedOnly) {
            return userDecorationRepository.findAllByUserIdAndIsEquipped(memberId, true)
                    .flatMap(this::mapToDecorationResponse);
        }
        return userDecorationRepository.findAllByUserId(memberId)
                .flatMap(this::mapToDecorationResponse);
    }

    private Mono<DecorationResponse> mapToDecorationResponse(UserDecoration userDecoration) {
        return mapToDecoration(userDecoration)
                .map(decoration -> new DecorationResponse(decoration, userDecoration.getIsEquipped()));
    }

    private Mono<Decoration> mapToDecoration(UserDecoration userDecoration) {
        return decorationRepository.findById(userDecoration.getDecorationId());
    }

    // ==========
    public Mono<Void> setDecoration(long memberId, DecorationRequest decorationRequest) {
        return mapToDecoType(decorationRequest)
                .flatMap(decoType -> userDecorationRepository.resetIsEquippedByMemberIdAndDecoType(memberId, decoType))
                .then(userDecorationRepository.setIsEquippedByMemberIdAndDecorationId(memberId, decorationRequest.decorationId()));
    }

    private Mono<DecoType> mapToDecoType(DecorationRequest decorationRequest) {
        return decorationRepository.findById(decorationRequest.decorationId())
                .map(Decoration::getDecoType);
    }
}
