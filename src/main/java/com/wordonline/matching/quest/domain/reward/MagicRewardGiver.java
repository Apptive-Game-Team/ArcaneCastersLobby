package com.wordonline.matching.quest.domain.reward;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.wordonline.matching.deck.domain.UserCard;
import com.wordonline.matching.deck.repository.UserCardRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Grants a number of copies of one magic card. This is the single reward path left after cards
 * and magics merged into one thing: a first-login grant is 3 copies of every default magic
 * (see {@code UserRepository.initUserMagic}), and a quest reward is however many copies of
 * whichever magic {@code reward_params} names for that quest.
 */
@Scope("prototype")
@Component("magic_rg")
@RequiredArgsConstructor
public class MagicRewardGiver implements RewardGiver {

    private static final int DEFAULT_REWARD_AMOUNT = 1;

    private final UserCardRepository userCardRepository;

    @ParamName("magic_id")
    private Long magicId;

    @ParamName("count")
    private Integer count = DEFAULT_REWARD_AMOUNT;

    @Override
    public Mono<Void> give(long userId) {
        var userCard = new UserCard(userId, magicId, count);
        return userCardRepository.save(userCard)
                .then();
    }

    @Override
    public String getRewardType() {
        return "MAGIC";
    }

    @Override
    public long getRewardId() {
        return magicId;
    }

    @Override
    public int getAmount() {
        return count;
    }
}
