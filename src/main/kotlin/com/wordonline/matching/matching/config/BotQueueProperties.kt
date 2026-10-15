package com.wordonline.matching.matching.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * The bot queue feeder ([com.wordonline.matching.matching.service.BotQueueFeeder]) keeps bot
 * tickets in the matching queue so bot-vs-bot games are created by the lobby's matching loop.
 */
@ConfigurationProperties("matching.bot-queue")
data class BotQueueProperties(
    /** Off stops new bot tickets; bot tickets already queued still match or expire. */
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofSeconds(10),
    /** Bot games wanted on an `ACTIVE` game server whose `servers.target_bot_sessions` is `NULL`. */
    val defaultTargetPerServer: Int = 1,
    /** Upper bound on bot tickets enqueued in one feeder run, so a raised target ramps up gradually. */
    val maxEnqueuePerTick: Int = 4,
)
