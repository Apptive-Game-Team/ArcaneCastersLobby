package com.wordonline.matching.matching.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("matching.practice")
data class PracticeMatchProperties(
    /**
     * How many bots nearest to the player's MMR the practice opponent is drawn from. 1 always
     * gives the single closest bot, so the same player would meet the same bot every time.
     */
    val nearestBotCount: Int = 3,
)
