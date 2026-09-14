package com.wordonline.matching.server.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [Server.callUrl] is the only place that decides which address a server-to-server call
 * goes out on. Pin its two branches here rather than only through the services that use it,
 * because every caller depends on this choice happening exactly once, in exactly one place.
 */
class ServerTest {

    private fun server(internalBaseUrl: String? = null) = Server(
        id = 1L,
        protocol = "https",
        domain = "blue.game.ac.theevilent.com",
        port = 443,
        state = ServerState.ACTIVE,
        type = ServerType.GAME,
        internalBaseUrl = internalBaseUrl,
    )

    @Test
    fun `internalBaseUrl이 있으면 그 값만 쓴다`() {
        val target = server(internalBaseUrl = "http://ac-game-blue:8080")

        assertThat(target.callUrl).isEqualTo("http://ac-game-blue:8080")
    }

    @Test
    fun `internalBaseUrl이 NULL이면 공개 주소 url을 쓴다`() {
        val target = server(internalBaseUrl = null)

        assertThat(target.callUrl).isEqualTo(target.url).isEqualTo("https://blue.game.ac.theevilent.com:443")
    }

    @Test
    fun `internalBaseUrl 유무와 무관하게 url은 항상 공개 주소다`() {
        val withInternal = server(internalBaseUrl = "http://ac-game-blue:8080")
        val withoutInternal = server(internalBaseUrl = null)

        assertThat(withInternal.url).isEqualTo("https://blue.game.ac.theevilent.com:443")
        assertThat(withoutInternal.url).isEqualTo("https://blue.game.ac.theevilent.com:443")
    }
}
