package com.wordonline.matching.session.domain;

import java.sql.Time;

import com.wordonline.matching.matching.dto.MatchedInfoDto;

public record SessionRecoveryInfo(
        long leftUserId,
        long rightUserId,
        String sessionId,
        String serverUrl,
        long expireAt,
        String webSocketUrl,
        String lockstepSimulationVersion,
        String mapType
) {

    private final static long TTL = 5 * 60 * 1000;

    public SessionRecoveryInfo(long leftUserId, long rightUserId, String sessionId, String serverUrl, long expireAt) {
        this(leftUserId, rightUserId, sessionId, serverUrl, expireAt, null, null, null);
    }

    public SessionRecoveryInfo(MatchedInfoDto matchedInfoDto) {
        this(
                matchedInfoDto.getLeftUser().id(),
                matchedInfoDto.getRightUser().id(),
                matchedInfoDto.getSessionId(),
                matchedInfoDto.getServer(),
                System.currentTimeMillis() + TTL,
                matchedInfoDto.getWebSocketUrl(),
                matchedInfoDto.getLockstepSimulationVersion(),
                matchedInfoDto.getMapType()
        );
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}
