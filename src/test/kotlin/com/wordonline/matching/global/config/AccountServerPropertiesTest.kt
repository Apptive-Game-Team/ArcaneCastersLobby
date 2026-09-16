package com.wordonline.matching.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [AccountServerProperties.jwksUri] is the only place that joins the account server's base URL
 * with the JWK Set path. A trailing slash on the configured URL must not produce a `//` in the
 * result, since that is exactly the shape `ACCOUNT_SERVER_URL` can arrive in.
 */
class AccountServerPropertiesTest {

    @Test
    fun `URL without a trailing slash gets the JWKS path appended`() {
        val properties = AccountServerProperties(url = "http://account-server:8080")

        assertThat(properties.jwksUri).isEqualTo("http://account-server:8080/.well-known/jwks")
    }

    @Test
    fun `URL with a trailing slash does not produce a double slash`() {
        val properties = AccountServerProperties(url = "http://account-server:8080/")

        assertThat(properties.jwksUri).isEqualTo("http://account-server:8080/.well-known/jwks")
    }
}
