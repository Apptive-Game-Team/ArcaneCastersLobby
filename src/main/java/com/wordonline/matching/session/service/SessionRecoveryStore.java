package com.wordonline.matching.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordonline.matching.matching.dto.MatchedInfoDto;
import com.wordonline.matching.session.domain.SessionRecoveryInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;


@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRecoveryStore {

    private static final String KEY_PREFIX = "matching:result:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<SessionRecoveryInfo> getSessionInfo(Long userId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + userId)
                .mapNotNull(json -> {
                    try {
                        return objectMapper.readValue(json, SessionRecoveryInfo.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize SessionRecoveryInfo for userId {}", userId, e);
                        return null;
                    }
                });
    }

    public Mono<Void> storeMatchInfo(MatchedInfoDto matchedInfoDto) {
        SessionRecoveryInfo info = new SessionRecoveryInfo(matchedInfoDto);
        String json;
        try {
            json = objectMapper.writeValueAsString(info);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SessionRecoveryInfo", e);
            return Mono.empty();
        }

        Mono<Boolean> storeLeft = storeForUser(matchedInfoDto.getLeftUser().id(), json);
        Mono<Boolean> storeRight = storeForUser(matchedInfoDto.getRightUser().id(), json);
        return Mono.when(storeLeft, storeRight);
    }

    private Mono<Boolean> storeForUser(Long userId, String json) {
        if (userId == null || userId < 0) return Mono.just(true);
        return redisTemplate.opsForValue().set(KEY_PREFIX + userId, json, SessionRecoveryInfo.TTL);
    }
}
