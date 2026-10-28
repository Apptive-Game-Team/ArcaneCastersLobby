package com.wordonline.matching.session.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "gameserver.lockstep")
data class LockstepProperties(
    val enabled: Boolean = false,
    val simulationVersion: String = "lockstep-v1",
)
