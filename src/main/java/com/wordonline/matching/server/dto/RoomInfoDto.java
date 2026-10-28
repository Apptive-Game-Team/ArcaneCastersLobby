package com.wordonline.matching.server.dto;

import java.time.Instant;

public record RoomInfoDto(
        String sessionId,
        Long leftUserId,
        Long rightUserId,
        String serverUrl,
        Instant createdAt,
        String mapType,
        String lockstepSimulationVersion
) {

}
