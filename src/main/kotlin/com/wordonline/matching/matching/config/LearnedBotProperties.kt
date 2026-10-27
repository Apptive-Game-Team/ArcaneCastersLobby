package com.wordonline.matching.matching.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "learned-bots")
data class LearnedBotProperties(
    val config: String = "",
    val timeout: Duration = Duration.ofSeconds(2),
)
