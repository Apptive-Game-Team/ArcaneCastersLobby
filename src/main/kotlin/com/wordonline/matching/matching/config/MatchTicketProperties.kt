package com.wordonline.matching.matching.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("matching.ticket")
data class MatchTicketProperties(
    val queueTimeout: Duration = Duration.ofMinutes(5),
    val allocationLease: Duration = Duration.ofSeconds(30),
    /**
     * How long a `QUEUED` ticket waits for a human opponent before the lobby matches it with
     * a bot of similar MMR. Measured from the ticket's `createdAt`.
     */
    val botFallbackAfter: Duration = Duration.ofSeconds(15),
    /**
     * How long a finished ticket stays readable so a reconnecting client can still see why
     * it ended. Only terminal tickets carry it; a `MATCHED` ticket must never expire while
     * its session is running.
     */
    val terminalTtl: Duration = Duration.ofMinutes(30),
    /**
     * How often the reconciler sweeps `MATCHED` tickets looking for sessions whose host
     * process is gone. Raising it far above a session's lifetime effectively disables the
     * sweep without a code change.
     */
    val matchedScanInterval: Duration = Duration.ofSeconds(30),
    /**
     * How often the reconciler re-checks tickets a client already reported but the lobby
     * could not verify. Much shorter than [matchedScanInterval] because such a ticket stays
     * `MATCHED`, and the client keeps re-entering the game scene until the verdict lands.
     */
    val pendingScanInterval: Duration = Duration.ofSeconds(3),
    /**
     * Grace period after a ticket reaches `MATCHED` before the reconciler will audit it.
     * Guards against auditing a session the game server has accepted but not yet finished
     * registering, which would answer `active=false` and look like a loss.
     */
    val matchedScanGrace: Duration = Duration.ofSeconds(30),
    /** Tickets audited per sweep, so one sweep cannot fan out unboundedly. */
    val matchedScanBatchSize: Long = 100,
)
