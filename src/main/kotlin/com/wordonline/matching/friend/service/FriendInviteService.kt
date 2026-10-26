package com.wordonline.matching.friend.service

import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.friend.domain.FriendEvent
import com.wordonline.matching.friend.domain.FriendEventType
import com.wordonline.matching.friend.domain.FriendInvite
import com.wordonline.matching.friend.domain.FriendInviteStatus
import com.wordonline.matching.friend.dto.FriendInviteDto
import com.wordonline.matching.friend.repository.FriendRepository
import com.wordonline.matching.matching.client.AccountClient
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class FriendInviteService(
    private val friendRepository: FriendRepository,
    private val accountClient: AccountClient,
    private val deckService: DeckService,
    private val matchingQueueRepository: MatchingQueueRepository,
    private val legacyGameMatchService: LegacyGameMatchService,
    private val friendEventService: FriendEventService,
    private val friendPresenceService: FriendPresenceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val invites = ConcurrentHashMap<String, FriendInvite>()

    suspend fun invite(inviterId: Long, friendId: Long): FriendInviteDto {
        if (!friendRepository.existsByUserIdAndFriendId(inviterId, friendId)) {
            throw IllegalStateException("User $friendId is not your friend")
        }

        check(friendPresenceService.status(inviterId) == "Online" &&
            friendPresenceService.status(friendId) == "Online") { "Both players must be online and available" }

        val inviterDeckValid = deckService.hasValidSelectedDeck(inviterId).awaitSingle()
        if (!inviterDeckValid) {
            throw IllegalStateException("You do not have a valid selected deck")
        }

        val friendDeckValid = deckService.hasValidSelectedDeck(friendId).awaitSingle()
        if (!friendDeckValid) {
            throw IllegalStateException("Friend does not have a valid selected deck")
        }

        cleanExpiredInvites()

        val activeInvite = invites.values.firstOrNull {
            it.status == FriendInviteStatus.PENDING &&
                ((it.inviterId == inviterId && it.inviteeId == friendId) ||
                 (it.inviterId == friendId && it.inviteeId == inviterId)) &&
                Instant.now().isBefore(it.expiresAt)
        }
        if (activeInvite != null) {
            throw IllegalStateException("An active invite already exists between users")
        }

        val inviter = accountClient.getMemberSuspend(inviterId)
        val friend = accountClient.getMemberSuspend(friendId)

        val inviteId = UUID.randomUUID().toString()
        val now = Instant.now()
        val invite = FriendInvite(
            inviteId = inviteId,
            inviterId = inviterId,
            inviterName = inviter.displayName,
            inviteeId = friendId,
            inviteeName = friend.displayName,
            status = FriendInviteStatus.PENDING,
            createdAt = now,
            expiresAt = now.plusSeconds(30),
        )

        synchronized(invites) {
            cleanExpiredInvites()
            check(invites.values.none {
                it.status == FriendInviteStatus.PENDING &&
                    setOf(it.inviterId, it.inviteeId) == setOf(inviterId, friendId)
            }) { "An active invite already exists between users" }
            invites[inviteId] = invite
        }
        val dto = FriendInviteDto.from(invite)

        friendEventService.publish(
            friendId,
            FriendEvent(
                type = FriendEventType.FRIEND_INVITE_RECEIVED,
                invite = dto,
                targetUserId = inviterId,
            )
        )

        return dto
    }

    suspend fun acceptInvite(inviteeId: Long, inviteId: String): MatchedInfoDto {
        val invite = invites[inviteId]
            ?: throw IllegalArgumentException("Invite not found: $inviteId")

        if (invite.inviteeId != inviteeId) {
            throw IllegalStateException("You are not the recipient of this invite")
        }

        if (invite.status != FriendInviteStatus.PENDING) {
            throw IllegalStateException("Invite is no longer pending: ${invite.status}")
        }

        if (Instant.now().isAfter(invite.expiresAt)) {
            cleanExpiredInvites()
            throw IllegalStateException("Invite has expired")
        }

        val sessionId = "friend-${matchingQueueRepository.nextSessionId().awaitSingle()}"
        val sessionDto = SessionDto.PVP(sessionId, invite.inviterId, invite.inviteeId)
        val placement = legacyGameMatchService.createSession(sessionDto)
        val matchInfo = placement.matchInfo

        // 방 생성 중 도착한 수신 확인과 revision을 이전 스냅샷으로 덮어쓰지 않는다.
        val updatedInvite = invites.compute(inviteId) { _, current ->
            val latest = current ?: invite
            latest.copy(status = FriendInviteStatus.ACCEPTED, matchInfo = matchInfo, revision = latest.revision + 1)
        }!!

        val readyEvent = FriendEvent(
            type = FriendEventType.FRIEND_MATCH_READY,
            invite = FriendInviteDto.from(updatedInvite),
            matchInfo = matchInfo,
        )

        friendEventService.publish(invite.inviterId, readyEvent)
        friendEventService.publish(invite.inviteeId, readyEvent)

        log.info("Friend match session created: sessionId={}, inviter={}, invitee={}", sessionId, invite.inviterId, inviteeId)
        return matchInfo
    }

    suspend fun rejectInvite(inviteeId: Long, inviteId: String): FriendInviteDto {
        val invite = invites[inviteId]
            ?: throw IllegalArgumentException("Invite not found: $inviteId")

        if (invite.inviteeId != inviteeId) {
            throw IllegalStateException("You are not the recipient of this invite")
        }

        val updated = finishPending(inviteId, FriendInviteStatus.REJECTED)
        val dto = FriendInviteDto.from(updated)

        friendEventService.publish(
            invite.inviterId,
            FriendEvent(
                type = FriendEventType.FRIEND_INVITE_REJECTED,
                invite = dto,
                targetUserId = inviteeId,
            )
        )

        return dto
    }

    suspend fun cancelInvite(inviterId: Long, inviteId: String): FriendInviteDto {
        val invite = invites[inviteId]
            ?: throw IllegalArgumentException("Invite not found: $inviteId")

        if (invite.inviterId != inviterId) {
            throw IllegalStateException("You are not the sender of this invite")
        }

        val updated = finishPending(inviteId, FriendInviteStatus.CANCELED)
        val dto = FriendInviteDto.from(updated)

        friendEventService.publish(
            invite.inviteeId,
            FriendEvent(
                type = FriendEventType.FRIEND_INVITE_CANCELED,
                invite = dto,
                targetUserId = inviterId,
            )
        )

        return dto
    }

    fun getPendingInvites(userId: Long): List<FriendInviteDto> {
        cleanExpiredInvites()
        return invites.values
            .filter { it.inviteeId == userId && it.status == FriendInviteStatus.PENDING && Instant.now().isBefore(it.expiresAt) }
            .sortedBy { it.createdAt }
            .map { FriendInviteDto.from(it) }
    }

    fun getSentInvites(userId: Long): List<FriendInviteDto> {
        cleanExpiredInvites()
        return invites.values.filter { it.inviterId == userId }
            .sortedBy { it.createdAt }.map { FriendInviteDto.from(it) }
    }

    fun acknowledgeInvite(userId: Long, inviteId: String): FriendInviteDto {
        cleanExpiredInvites()
        val updated = invites.compute(inviteId) { _, current ->
            val invite = requireNotNull(current) { "Invite not found" }
            check(invite.inviteeId == userId) { "You are not the recipient of this invite" }
            if (invite.status != FriendInviteStatus.PENDING || invite.deliveredAt != null) invite
            else invite.copy(deliveredAt = Instant.now(), revision = invite.revision + 1)
        }!!
        val dto = FriendInviteDto.from(updated)
        friendEventService.publish(updated.inviterId, FriendEvent(FriendEventType.FRIEND_INVITE_DELIVERED, invite = dto))
        return dto
    }

    private fun finishPending(inviteId: String, status: FriendInviteStatus): FriendInvite =
        invites.compute(inviteId) { _, current ->
            val invite = requireNotNull(current) { "Invite not found" }
            if (invite.status == status) invite
            else {
                check(invite.status == FriendInviteStatus.PENDING) { "Invite is no longer pending" }
                val next = if (!Instant.now().isBefore(invite.expiresAt)) FriendInviteStatus.EXPIRED else status
                invite.copy(status = next, revision = invite.revision + 1)
            }
        }!!

    private fun cleanExpiredInvites() {
        val now = Instant.now()
        for (id in invites.keys) {
            invites.computeIfPresent(id) { _, invite ->
                if (now.isAfter(invite.createdAt.plusSeconds(300))) null
                else if (invite.status == FriendInviteStatus.PENDING && !now.isBefore(invite.expiresAt)) {
                    val expired = invite.copy(status = FriendInviteStatus.EXPIRED, revision = invite.revision + 1)
                    val event = FriendEvent(FriendEventType.FRIEND_INVITE_EXPIRED, invite = FriendInviteDto.from(expired))
                    friendEventService.publish(invite.inviterId, event)
                    friendEventService.publish(invite.inviteeId, event)
                    expired
                } else invite
            }
        }
    }
}
