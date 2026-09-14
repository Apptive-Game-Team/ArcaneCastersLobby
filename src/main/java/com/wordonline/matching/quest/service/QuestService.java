package com.wordonline.matching.quest.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wordonline.matching.quest.domain.QuestState;
import com.wordonline.matching.quest.dto.QuestProgressResponseDto;
import com.wordonline.matching.quest.dto.QuestRewardDto;
import com.wordonline.matching.quest.entity.Quest;
import com.wordonline.matching.quest.entity.UserQuest;
import com.wordonline.matching.quest.repository.QuestRepository;
import com.wordonline.matching.quest.repository.RewardParamRepository;
import com.wordonline.matching.quest.repository.UserQuestRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Transactional
public class QuestService {

    private final QuestRepository questRepository;
    private final UserQuestRepository userQuestRepository;
    private final RewardParamRepository rewardParamRepository;
    private final QuestChecker questChecker;
    private final QuestRewardGiver rewardGiver;

    public Mono<List<QuestRewardDto>> checkQuestsWithRewards(long userId) {
        return questRepository.findAllByUserIdAndState(userId, QuestState.IN_PROGRESS)
                .filter(quest -> quest.getProgressChecker() != null)
                .filterWhen(quest -> questChecker.check(userId, quest))
                .flatMapSequential(quest -> rewardGiver.giveWithReward(userId, quest)
                        .flatMap(reward -> markQuestCompleted(userId, quest.getId())
                                .thenReturn(reward)))
                .collectList();
    }

    private Mono<Void> markQuestCompleted(long userId, long questId) {
        return userQuestRepository.setCompletedByUserIdAndQuestId(userId, questId);
    }

    @Transactional(readOnly = true)
    public Mono<QuestProgressResponseDto> findQuestProgressByDecoration(long userId, long decoId) {
        return findQuestProgress(userId, rewardParamRepository.findByNameAndValue("decoration_id", (int) decoId)
                .flatMap(rewardParam -> questRepository.findById(rewardParam.getQuestId())));
    }

    @Transactional(readOnly = true)
    public Mono<QuestProgressResponseDto> findQuestProgressByCard(long userId, long cardId) {
        return findQuestProgress(userId, rewardParamRepository.findByNameAndValue("magic_id", (int) cardId)
                .flatMap(rewardParam -> questRepository.findById(rewardParam.getQuestId())));
    }

    private Mono<QuestProgressResponseDto> findQuestProgress(long userId, Mono<Quest> questMono) {
        return questMono
                .flatMap(quest -> userQuestRepository.findByUserIdAndQuestId(userId, quest.getId())
                        .defaultIfEmpty(new UserQuest(null, quest.getId(), userId, QuestState.PENDING))
                        .flatMap(userQuest -> questChecker.getProgress(userId, quest)
                                .flatMap(progress -> {
                                    var currentState = userQuest.getState();
                                    var newState = currentState;
                                    if (currentState == QuestState.PENDING && progress > 0) {
                                        newState = QuestState.IN_PROGRESS;
                                    }
                                    if (progress >= quest.getRequireValue()) {
                                        newState = QuestState.COMPLETED;
                                    }

                                    if (currentState != newState) {
                                        var newUserQuest = new UserQuest(userQuest.getId(), userQuest.getQuestId(),
                                                userQuest.getUserId(), newState);
                                        return userQuestRepository.save(newUserQuest)
                                                .map(savedQuest -> new QuestProgressResponseDto(
                                                        savedQuest.getState(),
                                                        progress,
                                                        quest.getRequireValue()
                                                ));
                                    }
                                    return Mono.just(new QuestProgressResponseDto(
                                            currentState,
                                            progress,
                                            quest.getRequireValue()
                                    ));
                                })
                        )
                );
    }
}
