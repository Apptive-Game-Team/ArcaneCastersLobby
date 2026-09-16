package com.wordonline.matching.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where the account server lives.
 *
 * Bound to the same `team6515.server.account.url` key [com.wordonline.matching.matching.client.AccountClient]
 * already reads with `@Value`, so no new environment variable is introduced. This class exists
 * to derive [jwksUri] once, in a place the security configuration and its tests can reach
 * without duplicating the URI-joining logic.
 */
@ConfigurationProperties(prefix = "team6515.server.account")
data class AccountServerProperties(
    val url: String,
) {
    /**
     * The account server's JWK Set endpoint, used to verify every JWT the lobby accepts:
     * both member tokens and the service tokens [InternalApiSecurityConfig] checks.
     *
     * Trims a trailing slash from [url] first so the join never produces `//.well-known/jwks`.
     */
    val jwksUri: String
        get() = "${url.trimEnd('/')}/.well-known/jwks"
}
