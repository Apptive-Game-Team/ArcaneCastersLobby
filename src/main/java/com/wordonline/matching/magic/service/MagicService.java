package com.wordonline.matching.magic.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.magic.domain.UserMagic;
import com.wordonline.matching.magic.dto.MagicResponse;
import com.wordonline.matching.magic.repository.UserMagicRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Transactional
public class MagicService {

    private final UserMagicRepository userMagicRepository;

    @Transactional(readOnly = true)
    public Mono<MagicResponse> findAll(long userId) {
        return userMagicRepository.findAllByUserId(userId)
                .map(UserMagic::getMagicId)
                .collectList()
                .map(MagicResponse::new);
    }
}
