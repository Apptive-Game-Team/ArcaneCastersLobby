package com.wordonline.matching.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Origins allowed to send credentialed cross-site requests to the lobby.
 *
 * The list is explicit on purpose. With credentials allowed, `"*"` makes Spring echo whichever
 * Origin asked and add `Access-Control-Allow-Credentials: true`, so any website could call the
 * lobby through a logged-in member's browser and read the answer. This class refuses that value
 * at startup rather than letting the deployment run open.
 *
 * Only browsers are bound by this. The desktop and Android builds send no Origin, and the admin
 * server calls the lobby from its own backend.
 */
@ConfigurationProperties(prefix = "cors")
data class CorsProperties(
    val allowedOrigins: List<String>,
) {
    init {
        val origins = allowedOrigins.map(String::trim).filter(String::isNotEmpty)
        require(origins.isNotEmpty()) { "cors.allowed-origins must name at least one origin" }
        require(ANY_ORIGIN !in origins) {
            "cors.allowed-origins must name each origin. \"$ANY_ORIGIN\" together with" +
                " allowCredentials lets any website read a logged-in member's data."
        }
    }

    /** Spring origin patterns, which is what lets the localhost entry cover any dev port. */
    val originPatterns: List<String>
        get() = allowedOrigins.map(String::trim).filter(String::isNotEmpty)

    private companion object {
        const val ANY_ORIGIN = "*"
    }
}
