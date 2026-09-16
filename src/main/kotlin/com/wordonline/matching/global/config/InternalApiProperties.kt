package com.wordonline.matching.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Who may call the internal API under `/api/internal`.
 *
 * No credential lives here. Callers present a JWT signed by the account server's key, which
 * the lobby already verifies against the account server's JWK Set ([AccountServerProperties]),
 * so there is nothing to store, rotate or accidentally commit - only which of those tokens
 * count as a service caller.
 */
@ConfigurationProperties(prefix = "internal-api")
data class InternalApiProperties(
    /**
     * Authority a service token must carry in its `scope` claim, or empty to accept any
     * server token.
     *
     * Left empty by default because the authority has to exist as a row in the account
     * server before a token can carry it: shipping a mandatory default nobody has created
     * would lock out every caller. Set it once that authority exists - the account server
     * mints server tokens with an arbitrary authority set, so a game-server-only authority
     * narrows this endpoint to the game servers rather than to every internal service.
     */
    val requiredAuthority: String = "",
)
