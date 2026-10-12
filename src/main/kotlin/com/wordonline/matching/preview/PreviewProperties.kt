package com.wordonline.matching.preview

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "preview")
data class PreviewProperties(
    val timeout: Duration = Duration.ofSeconds(10),
    val maxClipBytes: Int = 4 * 1024 * 1024,
)
