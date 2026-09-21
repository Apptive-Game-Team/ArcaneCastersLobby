package com.wordonline.matching.global.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
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

    @Test
    fun `CORS allows the configured origins and no others`() {
        val properties = CorsProperties(listOf("https://arcanecasters.theevilent.com", "http://localhost:[*]"))

        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/match"))
        val configuration = requireNotNull(
            config.corsConfigurationSource(properties).getCorsConfiguration(exchange)
        )

        assertThat(configuration.allowedOriginPatterns)
            .containsExactly("https://arcanecasters.theevilent.com", "http://localhost:[*]")
        assertThat(configuration.allowedOrigins).isNull()
        assertThat(configuration.allowCredentials ?: false).isTrue()
    }

    @Test
    fun `a wildcard origin is refused at startup`() {
        assertThatThrownBy { CorsProperties(listOf("https://arcanecasters.theevilent.com", "*")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must name each origin")
    }

    @Test
    fun `an empty origin list is refused at startup`() {
        assertThatThrownBy { CorsProperties(listOf(" ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one origin")
    }
}
