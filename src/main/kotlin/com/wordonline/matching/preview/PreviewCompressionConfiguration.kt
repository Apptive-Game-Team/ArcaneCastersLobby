package com.wordonline.matching.preview

import org.springframework.boot.web.server.Compression
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

@Configuration
class PreviewCompressionConfiguration {
    @Bean
    fun previewJsonCompression() = WebServerFactoryCustomizer<ConfigurableReactiveWebServerFactory> { factory ->
        factory.setCompression(Compression().apply {
            enabled = true
            mimeTypes = arrayOf("application/json")
            minResponseSize = DataSize.ofKilobytes(1)
        })
    }
}
