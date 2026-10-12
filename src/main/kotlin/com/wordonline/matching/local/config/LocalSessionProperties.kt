package com.wordonline.matching.local.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Matches the desktop client hosts on its own machine: issuing a session and checking the result.
 *
 * @property enabled switch that turns local matches off without a deploy. When false the offline
 *   pack says so and `POST /api/local-sessions` answers 503
 * @property minimumWinMillis a reported win shorter than this is rejected as `TOO_SHORT`
 * @property dailyResultLimit accepted local results per user per UTC day; one more is rejected as
 *   `DAILY_LIMIT`
 * @property sessionTtl how long an issued session can still be reported
 */
@ConfigurationProperties(prefix = "local-sessions")
data class LocalSessionProperties(
    val enabled: Boolean = true,
    val minimumWinMillis: Long = 30_000,
    val dailyResultLimit: Long = 100,
    val sessionTtl: Duration = Duration.ofHours(2),
) {
    init {
        require(minimumWinMillis >= 0) { "local-sessions.minimum-win-millis must not be negative" }
        require(dailyResultLimit >= 0) { "local-sessions.daily-result-limit must not be negative" }
        require(!sessionTtl.isNegative && !sessionTtl.isZero) { "local-sessions.session-ttl must be positive" }
    }
}
