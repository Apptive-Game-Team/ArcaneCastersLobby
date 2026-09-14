package com.wordonline.matching.quest.domain.reward;

import com.wordonline.matching.deck.domain.UserCard;
import com.wordonline.matching.deck.repository.UserCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicRewardGiverTest {

    @Mock
    private UserCardRepository userCardRepository;

    @InjectMocks
    private MagicRewardGiver magicRewardGiver;

    @Test
    @DisplayName("보상_지급시_유저ID와_마법ID와_지정된_장수가_그대로_저장됨")
    void give_SavesUserIdMagicIdAndConfiguredCount() {
        ReflectionTestUtils.setField(magicRewardGiver, "magicId", 3L);
        ReflectionTestUtils.setField(magicRewardGiver, "count", 3);

        when(userCardRepository.save(any(UserCard.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(magicRewardGiver.give(7L))
                .verifyComplete();

        ArgumentCaptor<UserCard> captor = ArgumentCaptor.forClass(UserCard.class);
        verify(userCardRepository).save(captor.capture());
        UserCard saved = captor.getValue();
        assert saved.getUserId().equals(7L);
        assert saved.getMagicId().equals(3L);
        assert saved.getCount().equals(3);
    }

    @Test
    @DisplayName("reward_params에_count가_없으면_기본값_1장을_지급함")
    void give_DefaultsToOneCopyWhenCountParamMissing() {
        ReflectionTestUtils.setField(magicRewardGiver, "magicId", 5L);

        when(userCardRepository.save(any(UserCard.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(magicRewardGiver.give(1L))
                .verifyComplete();

        ArgumentCaptor<UserCard> captor = ArgumentCaptor.forClass(UserCard.class);
        verify(userCardRepository).save(captor.capture());
        assert captor.getValue().getCount().equals(1);
    }
}
