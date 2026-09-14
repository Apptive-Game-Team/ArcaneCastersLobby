package com.wordonline.matching.quest.service;

import com.wordonline.matching.quest.domain.QuestState;
import com.wordonline.matching.quest.dto.QuestProgressResponseDto;
import com.wordonline.matching.quest.dto.QuestRewardDto;
import com.wordonline.matching.quest.entity.Quest;
import com.wordonline.matching.quest.entity.RewardParam;
import com.wordonline.matching.quest.entity.UserQuest;
import com.wordonline.matching.quest.repository.QuestRepository;
import com.wordonline.matching.quest.repository.RewardParamRepository;
import com.wordonline.matching.quest.repository.UserQuestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestServiceTest {

    @Mock
    private QuestRepository questRepository;
    @Mock
    private UserQuestRepository userQuestRepository;
    @Mock
    private RewardParamRepository rewardParamRepository;
    @Mock
    private QuestChecker questChecker;
    @Mock
    private QuestRewardGiver rewardGiver;

    @InjectMocks
    private QuestService questService;

    private Quest quest;
    private UserQuest userQuest;

    @BeforeEach
    void setUp() {
        quest = new Quest(1L, "checker", 100, "giver");
        userQuest = new UserQuest(1L, 1L, 1L, QuestState.IN_PROGRESS);
    }

    @Test
    @DisplayName("데코ID로_퀘스트_진행상황_조회_성공")
    void findQuestProgressByDecoration_Success() {
        long userId = 1L;
        long decoId = 1L;
        int progress = 50;
        RewardParam rewardParam = new RewardParam(1L, quest.getId(), "decoration_id", (int) decoId);

        when(rewardParamRepository.findByNameAndValue("decoration_id", (int) decoId)).thenReturn(Mono.just(rewardParam));
        when(questRepository.findById(quest.getId())).thenReturn(Mono.just(quest));
        when(userQuestRepository.findByUserIdAndQuestId(userId, quest.getId())).thenReturn(Mono.just(userQuest));
        when(questChecker.getProgress(userId, quest)).thenReturn(Mono.just(progress));

        Mono<QuestProgressResponseDto> result = questService.findQuestProgressByDecoration(userId, decoId);

        StepVerifier.create(result)
                .expectNextMatches(response ->
                        response.getState() == QuestState.IN_PROGRESS &&
                        response.getProgress() == progress &&
                        response.getRequireValue() == quest.getRequireValue()
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("카드ID로_퀘스트_진행상황_조회_성공")
    void findQuestProgressByCard_Success() {
        long userId = 1L;
        long cardId = 1L;
        int progress = 50;
        RewardParam rewardParam = new RewardParam(1L, quest.getId(), "magic_id", (int) cardId);

        when(rewardParamRepository.findByNameAndValue("magic_id", (int) cardId)).thenReturn(Mono.just(rewardParam));
        when(questRepository.findById(quest.getId())).thenReturn(Mono.just(quest));
        when(userQuestRepository.findByUserIdAndQuestId(userId, quest.getId())).thenReturn(Mono.just(userQuest));
        when(questChecker.getProgress(userId, quest)).thenReturn(Mono.just(progress));

        Mono<QuestProgressResponseDto> result = questService.findQuestProgressByCard(userId, cardId);

        StepVerifier.create(result)
                .expectNextMatches(response ->
                        response.getState() == QuestState.IN_PROGRESS &&
                        response.getProgress() == progress &&
                        response.getRequireValue() == quest.getRequireValue()
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("퀘스트_진행중에_상태변경_성공")
    void findQuestProgress_StateChange_Success() {
        long userId = 1L;
        long cardId = 1L;
        int progress = 100; // Progress is complete
        Quest localQuest = new Quest(2L, "checker2", 100, "giver2");
        RewardParam rewardParam = new RewardParam(1L, localQuest.getId(), "magic_id", (int) cardId);
        UserQuest pendingUserQuest = new UserQuest(1L, 2L, 1L, QuestState.PENDING);
        UserQuest completedUserQuest = new UserQuest(1L, 2L, 1L, QuestState.COMPLETED);

        when(rewardParamRepository.findByNameAndValue(anyString(), anyInt())).thenReturn(Mono.just(rewardParam));
        when(questRepository.findById(anyLong())).thenReturn(Mono.just(localQuest));
        when(userQuestRepository.findByUserIdAndQuestId(anyLong(), anyLong())).thenReturn(Mono.just(pendingUserQuest));
        when(questChecker.getProgress(anyLong(), any(Quest.class))).thenReturn(Mono.just(progress));
        when(userQuestRepository.save(any(UserQuest.class))).thenReturn(Mono.just(completedUserQuest));

        Mono<QuestProgressResponseDto> result = questService.findQuestProgressByCard(userId, cardId);

        StepVerifier.create(result)
                .expectNextMatches(response ->
                        response.getState() == QuestState.COMPLETED &&
                        response.getProgress() == progress &&
                        response.getRequireValue() == localQuest.getRequireValue()
                )
                .verifyComplete();
    }

    @Test
    @DisplayName("checkQuestsWithRewards returns completed quest rewards")
    void checkQuestsWithRewards_Success() {
        long userId = 1L;
        Quest secondQuest = new Quest(2L, "checker2", 50, "giver2");
        QuestRewardDto firstReward = new QuestRewardDto("CARD", 10L, 3, quest.getId());
        QuestRewardDto secondReward = new QuestRewardDto("MAGIC", 20L, 1, secondQuest.getId());

        when(questRepository.findAllByUserIdAndState(userId, QuestState.IN_PROGRESS))
                .thenReturn(Flux.just(quest, secondQuest));
        when(questChecker.check(userId, quest)).thenReturn(Mono.just(true));
        when(questChecker.check(userId, secondQuest)).thenReturn(Mono.just(true));
        when(rewardGiver.giveWithReward(userId, quest)).thenReturn(Mono.just(firstReward));
        when(rewardGiver.giveWithReward(userId, secondQuest)).thenReturn(Mono.just(secondReward));
        when(userQuestRepository.setCompletedByUserIdAndQuestId(userId, quest.getId())).thenReturn(Mono.empty());
        when(userQuestRepository.setCompletedByUserIdAndQuestId(userId, secondQuest.getId())).thenReturn(Mono.empty());

        StepVerifier.create(questService.checkQuestsWithRewards(userId))
                .expectNext(List.of(firstReward, secondReward))
                .verifyComplete();
    }
}
