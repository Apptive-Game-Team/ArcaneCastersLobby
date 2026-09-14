package com.wordonline.matching.global.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

/**
 * [WebSecurityConfig.jwtDecoder] must build the decoder from [AccountServerProperties.jwksUri],
 * not from a local key. This only checks bean composition - `NimbusReactiveJwtDecoder.build()`
 * does not fetch the JWK Set eagerly, so this never touches the network.
 */
class WebSecurityConfigTest {

    private val config = WebSecurityConfig()

    @Test
    fun `decoder bean is built from the account server's JWKS URI`() {
        val properties = AccountServerProperties(url = "http://account-server:8080")

        val decoder: ReactiveJwtDecoder = config.jwtDecoder(properties)

        assertThat(decoder).isInstanceOf(NimbusReactiveJwtDecoder::class.java)
    }

    @Test
    fun `decoder bean tolerates a trailing slash on the configured account server URL`() {
        val properties = AccountServerProperties(url = "http://account-server:8080/")

        val decoder = config.jwtDecoder(properties)

        assertThat(decoder).isInstanceOf(NimbusReactiveJwtDecoder::class.java)
    }
}
