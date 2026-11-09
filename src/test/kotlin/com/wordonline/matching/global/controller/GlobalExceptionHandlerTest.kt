package com.wordonline.matching.global.controller

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTest {
    private val client = WebTestClient.bindToController(Fixture())
        .controllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `없는 API의 404를 500으로 바꾸지 않는다`() {
        client.get().uri("/missing-resource").exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `잘못된 요청의 400을 유지한다`() {
        client.get().uri("/invalid-request").exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `허용하지 않는 메서드의 405와 Allow 헤더를 유지한다`() {
        client.get().uri("/wrong-method").exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectHeader().valueEquals("Allow", "POST")
    }

    @Test
    fun `예상하지 못한 서버 오류는 500으로 반환한다`() {
        client.get().uri("/unexpected-error").exchange()
            .expectStatus().is5xxServerError
    }

    @RestController
    class Fixture {
        @GetMapping("/missing-resource")
        fun missingResource(): String = throw NoResourceFoundException("api/users/mine/progression/sync")

        @GetMapping("/invalid-request")
        fun invalidRequest(): String = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request")

        @GetMapping("/wrong-method")
        fun wrongMethod(): String = throw MethodNotAllowedException(HttpMethod.GET, listOf(HttpMethod.POST))

        @GetMapping("/unexpected-error")
        fun unexpectedError(): String = throw IllegalStateException("Unexpected error")
    }
}
