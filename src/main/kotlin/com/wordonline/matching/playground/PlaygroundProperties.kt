package com.wordonline.matching.playground

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "playground")
data class PlaygroundProperties(
    val enabled: Boolean = false,
    val requestTimeout: Duration = Duration.ofSeconds(5),
)
