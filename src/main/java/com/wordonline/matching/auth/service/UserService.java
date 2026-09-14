package com.wordonline.matching.auth.service;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.auth.domain.User;
import com.wordonline.matching.auth.domain.UserStatus;
import com.wordonline.matching.auth.dto.UserDetailResponseDto;
import com.wordonline.matching.auth.dto.UserResponseDto;
import com.wordonline.matching.auth.repository.UserRepository;
import com.wordonline.matching.matching.client.AccountClient;
import com.wordonline.matching.global.service.LocalizationService;
import com.wordonline.matching.matching.repository.MatchingQueueRepository;
import com.wordonline.matching.server.dto.RoomInfoDto;
import com.wordonline.matching.server.service.GameSessionService;
import com.wordonline.matching.session.service.SessionRecoveryStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AccountClient accountClient;
    private final LocalizationService localizationService;
    private final MatchingQueueRepository matchingQueueRepository;
    private final SessionRecoveryStore sessionRecoveryStore;
    private final GameSessionService gameSessionService;

    public Mono<UserResponseDto> getUser(long memberId) {
        return findUserDomain(memberId)
                .onErrorResume(e -> initialUser(memberId))
                .map(UserResponseDto::new);
    }

    private Mono<User> initialUser(long memberId) {
        return userRepository.insertUser(memberId)
                .then(userRepository.initUserMagic(memberId))
                .then(userRepository.initUserQuest(memberId))
                .then(userRepository.initUserDeck(memberId))
                .then(userRepository.findById(memberId));
    }

    public Mono<UserDetailResponseDto> getUserDetail(Long memberId) {
        return accountClient.getMember(memberId)
                .map(accountMemberResponseDto -> {
                    log.info(accountMemberResponseDto.toString());
                    return new UserDetailResponseDto(memberId, accountMemberResponseDto);
                });
    }

    public Mono<Void> deleteUser(long userId) {
        return userRepository.deleteById(userId);
    }

    private Mono<User> findUserDomain(long userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(throwAuthorizationDeniedException(userId));
    }

    private Mono<User> throwAuthorizationDeniedException(long userId) {
        return Mono.deferContextual(ctx -> {
            LocaleContext localeContext = ctx.get(LocaleContext.class);
            return Mono.error(
                    new AuthorizationDeniedException(
                            localizationService.getMessage(
                                    localeContext,
                                    "error.user.not.found",
                                    new Object[]{userId})));
        });
    }

    public Mono<Void> markMatching(Long userId) {
        if (userId == null || userId < 0){
            return Mono.empty();
        }

        return userRepository.updateStatus(userId, UserStatus.OnMatching)
                .then();
    }

    public Mono<Void> markPlaying(Long userId) {
        if (userId == null || userId < 0){
            return Mono.empty();
        }

        return userRepository.updateStatus(userId, UserStatus.OnPlaying)
                .then();
    }

    public Mono<Void> markOnline(Long userId) {
        if (userId == null || userId < 0){
            return Mono.empty();
        }

        return userRepository.updateStatus(userId, UserStatus.Online)
                .then();
    }

    public Mono<Long> getMmr(Long userId) {
        if (userId == null || userId < 0) return Mono.just(0L);
        return findUserDomain(userId)
                .map(user -> user.getMmr() != null ? user.getMmr() : 0L);
    }

    /**
     * Whether this player is still working through the tutorial, which routes their practice match
     * to the opponent that holds back. Reaching 1.0 means nothing is held back any more, so it is
     * the same thing as having finished. Missing rows and bots answer false.
     */
    public Mono<Boolean> isNovice(Long userId) {
        if (userId == null || userId < 0) {
            return Mono.just(false);
        }
        return findUserDomain(userId)
                .map(user -> user.getNoviceProgress() != null && user.getNoviceProgress() < 1.0f)
                // Failing closed keeps the player queueing - they meet an ordinary bot and keep the
                // mark for next time - but it must not be silent, or a novice who never meets the
                // tutorial opponent looks like a routing bug rather than a lookup that failed.
                .doOnError(error -> log.warn("Novice lookup failed for userId={}; treating as not a novice", userId, error))
                .onErrorReturn(false);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Mono<UserStatus> getStatus(Long userId) {
        if (userId == null || userId < 0){
            return Mono.empty();
        }

        return matchingQueueRepository.isInQueue(userId)
            .flatMap(isIn -> {
                if (isIn) {
                    return Mono.just(UserStatus.OnMatching);
                }

                return sessionRecoveryStore.getSessionInfo(userId)
                        .flatMap(sessionInfo -> gameSessionService.getAllGameSessions()
                                .map(roomList -> roomList.rooms().stream()
                                        .anyMatch(room -> isUserInRoom(userId, room))
                                        ? UserStatus.OnPlaying
                                        : UserStatus.Online))
                        .defaultIfEmpty(UserStatus.Online);
            });
    }

    private boolean isUserInRoom(Long userId, RoomInfoDto room) {
        return userId.equals(room.leftUserId()) || userId.equals(room.rightUserId());
    }
}
