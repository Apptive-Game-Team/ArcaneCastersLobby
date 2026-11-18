package com.wordonline.matching.session.domain;

import java.time.Duration;

import com.wordonline.matching.matching.dto.MatchedInfoDto;

public record SessionRecoveryInfo(
        long leftUserId,
        long rightUserId,
        String sessionId,
        String serverUrl,
        long expireAt,
        String mapType,
        int startProtocolVersion
) {

    public SessionRecoveryInfo(long leftUserId, long rightUserId, String sessionId, String serverUrl, long expireAt, String mapType) {
        this(leftUserId, rightUserId, sessionId, serverUrl, expireAt, mapType, 0);
    }

    // 준비 3분과 실제 경기 중에 복구 정보가 먼저 만료되지 않게 한다.
    public static final Duration TTL = Duration.ofMinutes(30);

    public SessionRecoveryInfo(MatchedInfoDto matchedInfoDto) {
        this(
                matchedInfoDto.getLeftUser().id(),
                matchedInfoDto.getRightUser().id(),
                matchedInfoDto.getSessionId(),
                matchedInfoDto.getServer(),
                System.currentTimeMillis() + TTL.toMillis(),
                matchedInfoDto.getMapType(),
                matchedInfoDto.getStartProtocolVersion()
        );
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
