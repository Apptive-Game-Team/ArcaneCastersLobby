package com.wordonline.matching.quest.service;

import com.wordonline.matching.deck.repository.UserCardRepository;
import com.wordonline.matching.quest.domain.reward.MagicRewardGiver;
import com.wordonline.matching.quest.entity.Quest;
import com.wordonline.matching.quest.entity.RewardParam;
import com.wordonline.matching.quest.repository.RewardParamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestRewardGiverTest {

    @Test
    @DisplayName("마법 보상은 reward_params 를 채운 뒤에 보상 id 를 읽는다")
    void magicRewardReadsItsIdAfterTheParamsAreFilled() {
        UserCardRepository userCardRepository = mock(UserCardRepository.class);
        when(userCardRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        RewardParamRepository rewardParamRepository = mock(RewardParamRepository.class);
        when(rewardParamRepository.findByQuestIdAndName(9L, "magic_id"))
                .thenReturn(Mono.just(new RewardParam(1L, 9L, "magic_id", 83)));
        when(rewardParamRepository.findByQuestIdAndName(9L, "count")).thenReturn(Mono.empty());

        // A fresh prototype bean, as the application context hands out: magicId is still null.
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean("magic_rg", com.wordonline.matching.quest.domain.reward.RewardGiver.class))
                .thenReturn(new MagicRewardGiver(userCardRepository));

        Quest quest = mock(Quest.class);
        when(quest.getId()).thenReturn(9L);
        when(quest.getRewardGiver()).thenReturn("magic_rg");

        QuestRewardGiver giver = new QuestRewardGiver(rewardParamRepository, applicationContext);

        StepVerifier.create(giver.giveWithReward(5L, quest))
                .assertNext(reward -> {
                    org.assertj.core.api.Assertions.assertThat(reward.rewardId()).isEqualTo(83L);
                })
                .verifyComplete();
    }
}
