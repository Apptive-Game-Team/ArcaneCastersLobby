package com.wordonline.matching.server.service

import com.wordonline.matching.server.client.GameServerClient
import com.wordonline.matching.server.dto.RoomInfoDto
import com.wordonline.matching.server.dto.RoomListDto
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.session.repository.ServerRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.Comparator

/**
 * Ported from Java: the request-time internal/public address fallback below needs the failure
 * to actually propagate out of [GameServerClient.getGameSessionsOrThrow] so it can be retried,
 * which is more than "a line or two" of change to the original file.
 */
@Service
class GameSessionService(
    private val serverRepository: ServerRepository,
    private val gameServerClient: GameServerClient,
    private val serverHealthRegistry: ServerHealthRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val roomComparator: Comparator<RoomInfoDto> =
        Comparator.comparing(RoomInfoDto::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RoomInfoDto::sessionId)

    fun getAllGameSessions(): Mono<RoomListDto> =
        serverRepository.findAllByTypeAndState(ServerType.GAME, ServerState.ACTIVE)
            .flatMap { server -> fetchGameSessionsFromServer(server) }
            .collectList()
            .map { listOfLists ->
                // Each game server returns its own rooms oldest first, but flatMap interleaves the
                // servers non-deterministically, so the merged list has to be re-sorted or the same
                // set of rooms comes back in a different order on every request.
                val allRooms = listOfLists.flatten().sortedWith(roomComparator)
                RoomListDto(allRooms)
            }

    /**
     * The outbound request goes over the registry's recorded call address (internal when the
     * last health probe answered there, public otherwise), but [RoomInfoDto.serverUrl] must
     * stay [Server.url]: it rides `MatchedInfoDto.server` to the Unity client, which cannot
     * reach the docker network.
     *
     * A request that goes out on the internal address and fails (timeout or any other
     * exception) is retried exactly once on the public address, since the health probe that
     * picked the internal address can be stale. A request already on the public address is not
     * retried - that would send the identical request twice - and simply collapses to an empty
     * room list, matching the previous behavior for an unreachable server.
     */
    private fun fetchGameSessionsFromServer(server: Server): Mono<List<RoomInfoDto>> {
        val publicUrl = server.url
        val callUrl = serverHealthRegistry.callUrl(server)

        return gameServerClient.getGameSessionsOrThrow(callUrl)
            .onErrorResume { error ->
                if (callUrl == publicUrl) {
                    Mono.error(error)
                } else {
                    log.warn(
                        "Failed to fetch game sessions from server {} via internal address {}; " +
                            "retrying on public address {}: {}",
                        server.id,
                        callUrl,
                        publicUrl,
                        error.toString(),
                    )
                    gameServerClient.getGameSessionsOrThrow(publicUrl)
                }
            }
            .onErrorResume { error ->
                log.error("Failed to fetch game sessions from server: {}", publicUrl, error)
                Mono.just(RoomListDto(emptyList()))
            }
            .map { roomListDto ->
                // GameServerClient ensures roomListDto is never null and always contains a list (may be empty)
                roomListDto.rooms().map { room ->
                    RoomInfoDto(
                        room.sessionId(),
                        room.leftUserId(),
                        room.rightUserId(),
                        publicUrl,
                        room.createdAt(),
                    )
                }
            }
    }
}
