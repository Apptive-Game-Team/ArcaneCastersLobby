package com.wordonline.matching.quest.service;

import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.wordonline.matching.quest.domain.reward.ParamName;
import com.wordonline.matching.quest.domain.reward.RewardGiver;
import com.wordonline.matching.quest.dto.QuestRewardDto;
import com.wordonline.matching.quest.entity.Quest;
import com.wordonline.matching.quest.repository.RewardParamRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuestRewardGiver {

    private final RewardParamRepository rewardParamRepository;
    private final ApplicationContext applicationContext;

    public Mono<Void> give(long userId, Quest quest) {
        return giveWithReward(userId, quest).then();
    }

    public Mono<QuestRewardDto> giveWithReward(long userId, Quest quest) {
        RewardGiver rewardGiver = findRewardGiver(quest);
        if (rewardGiver == null) {
            return Mono.empty();
        }

        return fillParam(rewardGiver, quest.getId())
                .then(Mono.defer(() -> rewardGiver.give(userId)))
                // Built only after fillParam has run: a reward giver is a prototype bean whose
                // params are null until then, so thenReturn(new ...) read a null reward id at
                // assembly time and threw.
                .then(Mono.fromCallable(() -> new QuestRewardDto(
                        rewardGiver.getRewardType(),
                        rewardGiver.getRewardId(),
                        rewardGiver.getAmount(),
                        quest.getId()
                )));
    }

    private RewardGiver findRewardGiver(Quest quest) {
        try {
            return applicationContext.getBean(quest.getRewardGiver(), RewardGiver.class);
        } catch (NoSuchBeanDefinitionException | BeanNotOfRequiredTypeException e) {
            log.error("[Error] error while find reward giver", e);
            return null;
        }
    }

    private Mono<Void> fillParam(RewardGiver rewardGiver, long questId) {
        return Flux.fromArray(rewardGiver.getClass().getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ParamName.class))
                .flatMap(field -> {
                    field.setAccessible(true);
                    ParamName paramName = field.getAnnotation(ParamName.class);
                    return rewardParamRepository.findByQuestIdAndName(questId, paramName.value())
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(rewardParam -> {
                                try {
                                    field.set(rewardGiver, convertToFieldType(rewardParam.getValue(), field.getType()));
                                } catch (IllegalAccessException e) {
                                    log.error("[Error] fill field with reflection");
                                    return Mono.error(e);
                                }
                                return Mono.empty();
                            })
                            .then();
                })
                .then();
    }

    private Object convertToFieldType(Object value, Class<?> targetType) {
        if (value == null) return null;

        // 1. 이미 타입이 맞다면 그대로 반환
        if (targetType.isInstance(value)) {
            return value;
        }

        // 2. 숫자 타입인 경우 (Integer -> Long 등 처리)
        if (value instanceof Number) {
            Number num = (Number) value;
            if (targetType == Long.class || targetType == long.class) return num.longValue();
            if (targetType == Integer.class || targetType == int.class) return num.intValue();
            if (targetType == Double.class || targetType == double.class) return num.doubleValue();
            if (targetType == Float.class || targetType == float.class) return num.floatValue();
            if (targetType == Short.class || targetType == short.class) return num.shortValue();
        }

        // 3. 문자열 변환 요청 시
        if (targetType == String.class) {
            return String.valueOf(value);
        }

        // 4. 그 외의 경우 (최대한 캐스팅 시도)
        return targetType.cast(value);
    }
}
